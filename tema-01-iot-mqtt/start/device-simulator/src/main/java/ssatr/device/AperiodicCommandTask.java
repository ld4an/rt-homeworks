package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK APERIODIC: execută comenzile primite de la business service.
 * <p>
 * Comenzile sosesc oricând, pe MQTT, fără un interval minim între ele, și sunt
 * puse în coada commands. Fiecare comandă eliberează un job al taskului.
 * Comenzi care trebuie suportate:
 * <p>
 * SET_INTERVAL <ms> schimbă perioada T a taskului periodic
 * SET_THRESHOLD <valoare> schimbă pragul de alarmă
 * RESET readuce senzorul la valoarea inițială
 * STATUS raportează perioada și pragul curente
 * <p>
 * După executarea fiecărei comenzi, device-ul publică pe
 * Config.statusTopic(deviceId)
 * o confirmare: "deviceId;timestamp;text", de exemplu
 * "device-1;1727600000000;OK SET_INTERVAL 1000".
 */
public class AperiodicCommandTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<String> commands;
    private final PeriodicTelemetryTask periodic;

    public AperiodicCommandTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                BlockingQueue<String> commands, PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.commands = commands;
        this.periodic = periodic;
    }

    @Override
    public void run() {
        // TODO 3: implementați taskul aperiodic:
        //   - preluați comenzile din coadă: commands.take()
        //   - separați numele comenzii de parametru: command.split(" ")
        //   - executați comanda (periodic.setPeriodMs(...), sensor.reset() etc.)
        //   - publicați confirmarea pe Config.statusTopic(deviceId)
        //   - o comandă necunoscută sau un parametru invalid nu trebuie să
        //     oprească taskul: publicați "ERROR <motiv>"
        //
        // Vezi exemplul exemple/01-taskuri/.../AperiodicTaskExample.java
        try {
            while (true) {
                String comanda = commands.take();
                try {
                    String[] comandaSplit = comanda.trim().split("\\s+");
                    switch (comandaSplit[0]) {
                        case "SET_INTERVAL":

                            if (comandaSplit.length != 2) {
                                throw new IllegalArgumentException("utilizare: SET_INTERVAL <ms>");
                            }

                            long interval = Long.parseLong(comandaSplit[1]);

                            if (interval <= 0) {
                                throw new IllegalArgumentException("Intervalul trebuie sa fie pozitiv");
                            }

                            periodic.setPeriodMs(interval);
                            String mesajInterval = deviceId +";"+ System.currentTimeMillis()+";OK SET_INTERVAL "+interval;
                            mqtt.publish(Config.statusTopic(deviceId), mesajInterval);
                            break;
                        case "SET_THRESHOLD":

                            if (comandaSplit.length != 2) {
                                throw new IllegalArgumentException("utilizare: SET_THRESHOLD <valoare>");
                            }

                            double threshold = Double.parseDouble(comandaSplit[1]);

                            if (!Double.isFinite(threshold)) {
                                throw new IllegalArgumentException("pragul trebuie sa fie un numar finit");
                            }

                            periodic.setThreshold(threshold);
                            String mesajThreshold = deviceId +";"+ System.currentTimeMillis()+";OK SET_THRESHOLD "+threshold;
                            mqtt.publish(Config.statusTopic(deviceId), mesajThreshold);
                            break;
                        case "STATUS":
                            if (comandaSplit.length != 1) {
                                throw new IllegalArgumentException("utilizare: STATUS");
                            }
                            String statusThreshold = String.format(Locale.US, "%.2f", periodic.getThreshold());
                            String raspuns = "OK STATUS periodMs =" + periodic.getPeriodMs() + " threshold=" + statusThreshold;
                            String mesajStatus = deviceId + ";" + System.currentTimeMillis() + ";" + raspuns;
                            mqtt.publish(Config.statusTopic(deviceId), mesajStatus);
                            break;
                        case "RESET":
                            if (comandaSplit.length != 1) {
                                throw new IllegalArgumentException("utilizare: RESET");
                            }
                            sensor.reset();
                            String mesajReset = deviceId + ";" + System.currentTimeMillis()+";OK RESET";
                            mqtt.publish(Config.statusTopic(deviceId), mesajReset);
                            break;
                        default:
                            throw new IllegalArgumentException("Comanda necunoscuta");
                    }
                    } catch(IllegalArgumentException e){
                        String mesaj = deviceId + ";" + System.currentTimeMillis() + ";ERROR " + e.getMessage();
                        mqtt.publish(Config.statusTopic(deviceId), mesaj);
                }
            }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
    }
}
