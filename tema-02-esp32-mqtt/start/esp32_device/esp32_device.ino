// Device-ul IoT din tema 01, de data aceasta pe un ESP32-C3 Super Mini.
//
// Aceeași logică și același protocol ca device-simulator din tema 01, cu trei
// taskuri FreeRTOS în loc de trei thread-uri Java:
//
//   task periodic --(coada de alarme)--> task sporadic
//        |                                     |
//        +---- telemetry ---> MQTT <--- alarm -+
//                              |
//                          commands --> command_received() --(coada de comenzi)--> task aperiodic ---> status
//
// Gata implementate (nu trebuie modificate):
//   config.h         setările: Wi-Fi, broker, STUDENT, valorile inițiale; DEVICE_ID
//                    se citește din adresa MAC a plăcii
//   connection.h/cpp Wi-Fi, ora exactă (SNTP), clientul MQTT, mqtt_publish(), epoch_ms()
//
// Așa cum este, schița se conectează la Wi-Fi și la broker și afișează pe
// Serial comenzile primite. Mai jos sunt implementate TODO 0 ... TODO 3.
// Exemplele de pornire sunt în laboratorul 0 din rt-labs-esp32c3; vezi
// exemple/README.md din folderul temei.
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include "config.h"
#include "connection.h"
#include <errno.h>
#include <cmath>
#include <stdlib.h>
#include <string.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/queue.h>

struct Command {
    char text[COMMAND_MAX_LEN];
};

QueueHandle_t alarm_queue;    // coada de alarme: temperatura depășește pragul
QueueHandle_t command_queue;  // coada de comenzi

struct AlarmEvent {
    float temperature;
    float threshold;
};

// Temperatura cipului, în °C, de la senzorul intern al ESP32-C3.
// Este temperatura siliciului, nu a camerei: imediat după pornirea Wi-Fi-ului
// poate fi în jur de 50 °C, apoi scade în câteva minute spre 35-40 °C.
// Rezoluția este de aproximativ 1 °C. Ca să provocați alarme, puneți pragul
// cu SET_THRESHOLD cu un grad sub valoarea citită.
float read_temperature(void) {
    return temperatureRead();
}

// TODO 1: TASK PERIODIC, cu perioada T (inițial PERIOD_MS_INITIAL).
//   Fiecare job:
//   - citește temperatura: read_temperature()
//   - publică telemetria: mqtt_publish("telemetry", "deviceId;timestamp;temperatura")
//     (timestamp = epoch_ms(), tipărit cu %lld; temperatura cu "%.2f")
//   - dacă temperatura depășește pragul (inițial THRESHOLD_INITIAL), pune
//     un eveniment în coada de alarme, fără să se blocheze (timeout 0).
//     Taskul periodic NU tratează alarma: o tratează taskul sporadic.
//   Eliberările respectă grila r_k = r_0 + kT: folosiți așteptare absolută
//   (xTaskDelayUntil), nu relativă (delay, vTaskDelay), ca să nu apară derivă.
//   Perioada și pragul se schimbă prin comenzi (taskul aperiodic), deci sunt
//   variabile globale volatile, citite la fiecare job.

volatile uint32_t periodMs = PERIOD_MS_INITIAL;  // perioada T a taskului periodic
volatile float threshold = THRESHOLD_INITIAL;    // pragul de alarmă, în °

// Limite alese pentru aplicație, în milisecunde (nu impuse de enunț).
const uint32_t MIN_PERIOD_MS = 100;
const uint32_t MAX_PERIOD_MS = 60000;

void periodic_task(void* parameter) {
    TickType_t lastRelease = xTaskGetTickCount();

    for (;;) {
        int64_t timestamp = epoch_ms();
        float temperatura = read_temperature();
        float prag = threshold;
        Serial.printf("[periodic] temperatura=%.2f, prag=%.2f\n", temperatura, prag);

        char message[120];
        snprintf(message, sizeof(message), "%s;%lld;%.2f", DEVICE_ID, (long long)timestamp, temperatura);

        mqtt_publish("telemetry", message);

        if (temperatura > prag) {
            AlarmEvent event;
            event.temperature = temperatura;
            event.threshold = prag;
            if (xQueueSend(alarm_queue, &event, 0) != pdTRUE) {
                Serial.println("[periodic] coada de alarme plină");
            }
        }
        xTaskDelayUntil(&lastRelease, pdMS_TO_TICKS(periodMs));
    }
}

// TODO 2: TASK SPORADIC, tratează alarmele de temperatură.
//   - așteaptă un eveniment din coada de alarme
//   - dacă de la ultima alarmă publicată au trecut mai puțin de
//     MIN_INTERARRIVAL_MS, respinge evenimentul (afișați un mesaj pe Serial)
//   - altfel publică: mqtt_publish("alarm", "deviceId;timestamp;temperatura;prag")
//   Pentru intervale folosiți un timp monoton, millis(), nu epoch_ms().

void sporadic_task(void* parameter) {
    uint32_t lastAlarmTime = 0;
    bool hasPublishedAlarm = false;

    for (;;) {
        AlarmEvent event;
        if (xQueueReceive(alarm_queue, &event, portMAX_DELAY) != pdTRUE) {
            continue;
        }

        uint32_t currentTime = millis();
        uint32_t elapsedTime = currentTime - lastAlarmTime;

        if (hasPublishedAlarm && elapsedTime < MIN_INTERARRIVAL_MS) {
            Serial.println("[sporadic] alarma respinsa: interval prea scurt");
            continue;
        }

        char message[120];
        snprintf(message, sizeof(message), "%s;%lld;%.2f;%.2f", DEVICE_ID, (long long)epoch_ms(), event.temperature, event.threshold);
        if (mqtt_publish("alarm", message)) {
            lastAlarmTime = millis();
            hasPublishedAlarm = true;
            Serial.println("[sporadic] alarma publicata");
        } else {
            Serial.println("[sporadic] publicare alarma a esuat");
        }
    }
}

// TODO 3: TASK APERIODIC, execută comenzile primite de la business service.
//   Comenzile ajung în command_received() (mai jos), care le pune în coada de
//   comenzi; fiecare comandă eliberează un job al taskului:
//     SET_INTERVAL <ms>       schimbă perioada T a taskului periodic
//     SET_THRESHOLD <valoare> schimbă pragul de alarmă
//     RESET                   readuce perioada și pragul la valorile inițiale
//     STATUS                  raportează perioada și pragul curente
//   După fiecare comandă publică o confirmare:
//     mqtt_publish("status", "deviceId;timestamp;OK SET_INTERVAL 1000")
//   O comandă necunoscută sau un parametru invalid nu oprește taskul: se
//   publică "ERROR <motiv>".
void publish_status(const char* text) {
    Serial.printf("[aperiodic] %s\n", text);
    char message[160];
    snprintf(message, sizeof(message), "%s;%lld;%s", DEVICE_ID, (long long)epoch_ms(), text);
    if (!mqtt_publish("status", message)) {
        Serial.println("[aperiodic] publicare status a esuat");
    }
}

void aperiodic_task(void* parameter) {
    for (;;) {
        Command command;
        if (xQueueReceive(command_queue, &command, portMAX_DELAY) != pdTRUE) {
            continue;
        }

        char* saveptr = NULL;
        char* name = strtok_r(command.text, " \t\r\n", &saveptr);
        char* arg = strtok_r(NULL, " \t\r\n", &saveptr);
        char* extra = strtok_r(NULL, " \t\r\n", &saveptr);

        if (name == NULL) {
            publish_status("ERROR comanda goala");
            continue;
        }


        if (extra != NULL) {
            publish_status("ERROR prea multe argumente");
            continue;
        }

        if (strcmp(name, "SET_INTERVAL") == 0) {
            if (arg == NULL) {
                publish_status("ERROR lipseste argumentul");
                continue;
            }

            // Păstrăm valoarea cu semn până după validare: -1000 nu trebuie
            // convertit într-un uint32_t pozitiv foarte mare.
            char* end;
            errno = 0;
            long value = strtol(arg, &end, 10);
            if (end == arg || *end != '\0' || errno == ERANGE ||
                value < (long)MIN_PERIOD_MS || value > (long)MAX_PERIOD_MS) {
                publish_status("ERROR perioada invalida (100..60000 ms)");
                continue;
            }

            uint32_t newPeriod = (uint32_t)value;
            if (pdMS_TO_TICKS(newPeriod) == 0) {
                publish_status("ERROR perioada mai mica decat un tick");
                continue;
            }
            periodMs = newPeriod;
            char response[80];
            snprintf(response, sizeof(response), "OK SET_INTERVAL %lu",
                     (unsigned long)newPeriod);
            publish_status(response);
        } else if (strcmp(name, "SET_THRESHOLD") == 0) {
            if (arg == NULL) {
                publish_status("ERROR lipseste argumentul");
                continue;
            }

            char* end;
            errno = 0;
            float newThreshold = strtof(arg, &end);
            // end verifică întregul argument; isfinite respinge NaN și infinit.
            if (end == arg || *end != '\0' || errno == ERANGE ||
                !std::isfinite(newThreshold)) {
                publish_status("ERROR prag invalid");
                continue;
            }
            threshold = newThreshold;
            char response[80];
            snprintf(response, sizeof(response), "OK SET_THRESHOLD %.2f", newThreshold);
            publish_status(response);
        } else if (strcmp(name, "RESET") == 0) {
            if (arg != NULL) {
                publish_status("ERROR RESET nu are argumente");
                continue;
            }
            periodMs = PERIOD_MS_INITIAL;
            threshold = THRESHOLD_INITIAL;
            publish_status("OK RESET");
        } else if (strcmp(name, "STATUS") == 0) {
            if (arg != NULL) {
                publish_status("ERROR STATUS nu are argumente");
                continue;
            }
            char response[96];
            snprintf(response, sizeof(response), "OK STATUS interval=%lu threshold=%.2f",
                     (unsigned long)periodMs, (double)threshold);
            publish_status(response);
        } else {
            publish_status("ERROR comanda necunoscuta");
        }
    }
}

// Apelată de clientul MQTT pentru fiecare comandă primită (vezi connection.h).
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);
    // TODO 3: puneți comanda în coada de comenzi, fără să vă blocați
    //   (timeout 0). text este valid doar în timpul acestui apel: elementul
    //   cozii trebuie să conțină textul însuși (de exemplu un struct cu
    //   char text[COMMAND_MAX_LEN]), nu un pointer.

    Command command;
    snprintf(command.text, sizeof(command.text), "%s", text);
    if (xQueueSend(command_queue, &command, 0) != pdTRUE) {
        // Callback-ul MQTT doar pune în coadă; publicarea poate bloca.
        Serial.println("[mqtt] coada de comenzi este plina");
    }
}

void setup() {
    Serial.begin(115200);
    // Fără așteptare la scriere: cu placa la USB, dar fără Serial Monitor deschis,
    // fiecare Serial.printf() ar aștepta până la 2 s să fie citit și ar întârzia
    // taskul care scrie. Așa, textul pe care nu-l citește nimeni se pierde.
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);  // așteptăm Serial Monitor, cel mult 3 s
    delay(200);
    Serial.println();
    Serial.printf("=== tema 02: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    wifi_connect();
    time_sync();

    // TODO 0 (a): creați cele două cozi (xQueueCreate): coada de alarme și
    //   coada de comenzi. Înainte de mqtt_start(): de acolo încolo pot sosi
    //   comenzi, iar command_received() le pune în coada de comenzi.

    alarm_queue = xQueueCreate(10, sizeof(AlarmEvent));  // coada de alarme: temperatura depășește pragul
    command_queue = xQueueCreate(10, sizeof(Command));  // coada de comenzi
    if (alarm_queue == NULL || command_queue == NULL) {
        Serial.println("[setup] memorie insuficienta pentru cozi");
        for (;;) delay(1000);
    }

    mqtt_start();

    // TODO 0 (b): porniți cele trei taskuri (xTaskCreate). După mqtt_start(),
    //   ca primele mesaje publicate să nu se piardă. Alegeți prioritățile
    //   între 2 și 19: peste loopTask (1), sub taskurile sistemului pentru
    //   evenimente și Wi-Fi (20, 23). Stiva, în octeți: cel puțin 4096.

    BaseType_t periodicCreated = xTaskCreate(periodic_task, "periodic", 4096, NULL, 5, NULL);
    BaseType_t sporadicCreated = xTaskCreate(sporadic_task, "sporadic", 4096, NULL, 5, NULL);
    BaseType_t aperiodicCreated = xTaskCreate(aperiodic_task, "aperiodic", 4096, NULL, 5, NULL);
    if (periodicCreated != pdPASS || sporadicCreated != pdPASS || aperiodicCreated != pdPASS) {
        Serial.println("[setup] nu s-au putut crea toate taskurile");
        for (;;) delay(1000);
    }
}

void loop() {
    // Totul se face în taskuri: loop() nu are nimic de făcut.
    delay(1000);
}
