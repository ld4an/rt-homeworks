package ssatr.device;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import ssatr.device.PeriodicTelemetryTask;

/**
 * TASK SPORADIC: tratează alarmele de temperatură.
 *
 * Evenimentele sosesc în coada alarmEvents (puse de taskul periodic).
 * Intervalul minim între sosiri este MIN_INTERARRIVAL_MS: între două eliberări
 * ale taskului (două alarme publicate) trebuie să treacă cel puțin atât.
 * Intervalul este impus de cod: evenimentele care sosesc mai des sunt
 * respinse, ca să nu fie inundat brokerul.
 */
public class SporadicAlarmTask implements Runnable {

    static final long MIN_INTERARRIVAL_MS = 5000;

    private final String deviceId;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;
    private final PeriodicTelemetryTask periodicTelemetryTask;

    public SporadicAlarmTask(String deviceId, MqttConnection mqtt, BlockingQueue<Double> alarmEvents, PeriodicTelemetryTask periodicTelemetryTask) {
        this.deviceId = deviceId;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
        this.periodicTelemetryTask = periodicTelemetryTask;
    }


    @Override
    public void run() {
        // TODO 2: implementați taskul sporadic:
        //   - așteptați un eveniment: alarmEvents.take()
        //   - dacă de la ultima eliberare au trecut mai puțin de
        //     MIN_INTERARRIVAL_MS, respingeți evenimentul (afișați un mesaj)
        //   - altfel publicați pe Config.alarmTopic(deviceId) mesajul
        //     "deviceId;timestamp;temperatura;prag"
        //     (pragul curent poate fi transmis împreună cu temperatura sau
        //      citit din PeriodicTelemetryTask; alegeți și justificați în raport)
        //
        // Vezi exemplul exemple/01-taskuri/.../SporadicTaskExample.java
        long minIntevalNs = MIN_INTERARRIVAL_MS *1_000_000L;
        long lastRelease = System.nanoTime() - minIntevalNs;


        try {
            while (true){
                double temperatura = alarmEvents.take();
                long now = System.nanoTime();
                long sinceLast = now - lastRelease;
                if(sinceLast<minIntevalNs){
                    System.out.printf("RESPINS: au trecut doar %d ms (interval minim %d ms)%n", sinceLast/1_000_000L, MIN_INTERARRIVAL_MS);
                    continue;
                }
                lastRelease = now;

                String topic = Config.alarmTopic(deviceId);
                long timestamp = System.currentTimeMillis();
                String tempFormatat = String.format(Locale.US, "%.2f", temperatura);
                String threshold = String.format(Locale.US, "%.2f", periodicTelemetryTask.getThreshold());
                String mesaj = deviceId+";"+timestamp+";"+tempFormatat+";"+threshold;
                mqtt.publish(topic, mesaj);

                // Am ales să citesc pragul prin metoda getThreshold() din PeriodicTelemetryTask,
                // păstrând în coadă doar temperatura. Această variantă permite folosirea
                // structurii existente BlockingQueue<Double>. Pragul inclus în mesaj este
                // cel curent la tratarea evenimentului și poate diferi de cel de la detectare
                // dacă între timp se execută o comandă SET_THRESHOLD.

            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
    }
}
