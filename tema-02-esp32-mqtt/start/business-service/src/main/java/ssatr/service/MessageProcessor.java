package ssatr.service;

import java.util.concurrent.BlockingQueue;

/**
 * Preia mesajele de un anumit tip ("telemetry", "alarm" sau "status") din coada
 * inbox, le salvează și actualizează dashboard-ul.
 * <p>
 * Formatul mesajelor (câmpuri separate prin ";"):
 * telemetry:  deviceId;timestamp;temperatura
 * alarm:      deviceId;timestamp;temperatura;prag
 * status:     deviceId;timestamp;text
 */
public class MessageProcessor implements Runnable {

    private final String kind;
    private final BlockingQueue<String> inbox;
    private final CsvStorage storage;
    private final Dashboard dashboard;

    public MessageProcessor(String kind, BlockingQueue<String> inbox, CsvStorage storage, Dashboard dashboard) {
        this.kind = kind;
        this.inbox = inbox;
        this.storage = storage;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        // TODO 4: într-o buclă:
        //   - preluați un mesaj: inbox.take()
        //   - salvați mesajul în fișierul CSV: storage.append(kind, mesaj)
        //     (CsvStorage este gata implementată)
        //   - actualizați dashboard-ul, în funcție de kind
        //   - alarmele și confirmările se afișează imediat în consolă
        //   - un mesaj greșit (format invalid) nu trebuie să oprească thread-ul
        while (true) {
            try {
                String mesaj = inbox.take();
                storage.append(kind, mesaj);
                String[] campuri = mesaj.split(";", -1);

                if (campuri.length < 2) {
                    throw new IllegalArgumentException("lipsesc deviceId sau timestamp");
                }

                String deviceId = campuri[0];
                long timestamp = Long.parseLong(campuri[1]);

                switch (kind) {
                    case "telemetry":
                        if (campuri.length != 3) {
                            throw new IllegalArgumentException("format telemetry invalid");
                        }
                        double temperaturaTelemetry = Double.parseDouble(campuri[2]);

                        dashboard.updateTelemetry(deviceId, timestamp, temperaturaTelemetry);
                        break;

                    case "alarm":
                        if (campuri.length != 4) {
                            throw new IllegalArgumentException("format alarm invalid");
                        }
                        double temperaturaAlarm = Double.parseDouble(campuri[2]);
                        double threshold = Double.parseDouble(campuri[3]);
                        dashboard.updateAlarm(deviceId, timestamp, temperaturaAlarm, threshold);
                        System.out.println("ALARMA " + deviceId + ": temperatura=" + temperaturaAlarm + ", prag=" + threshold);
                        break;

                    case "status":
                        if (campuri.length != 3) {
                            throw new IllegalArgumentException("format status invalid");
                        }
                        String text = campuri[2];
                        dashboard.updateStatus(deviceId, timestamp, text);
                        System.out.println("STATUS " + deviceId + ": " + text);
                        break;
                    default:
                        throw new IllegalArgumentException("Comanda necunoscuta");
                }
            } catch (IllegalArgumentException e) {
                System.out.println("Mesaj " + kind + " invalid: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }


        }

    }
}
