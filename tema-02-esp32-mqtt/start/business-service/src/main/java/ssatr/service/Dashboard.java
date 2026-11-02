package ssatr.service;


import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ultima stare cunoscută a fiecărui device: ultima temperatură, momentul
 * ultimului mesaj, numărul de alarme etc.
 * <p>
 * Este actualizată de thread-urile MessageProcessor și citită de CommandConsole,
 * deci accesul trebuie sincronizat.
 */
public class Dashboard {
    // TODO 5: alegeți structura de date pentru starea device-urilor
    //   (de exemplu un Map<String, ...> cu cheia deviceId)
    //   și adăugați metodele de actualizare apelate din MessageProcessor.

    private static class DeviceState {
        Double prag;
        Double temperatura;
        long ultimulMesaj;
        int numarAlarme;
        String ultimulStatus;
    }

    private final Map<String, DeviceState> devices = new HashMap<>();

    public synchronized void updateTelemetry(String deviceId, long timestamp, double temperatura) {
        DeviceState state = devices.get(deviceId);

        if (state == null) {
            state = new DeviceState();
            devices.put(deviceId, state);
        }

        state.temperatura = temperatura;
        state.ultimulMesaj = Math.max(state.ultimulMesaj, timestamp);
    }

    public synchronized void updateAlarm(String deviceId, long timestamp, double temperatura, double prag) {
        DeviceState state = devices.get(deviceId);

        if (state == null) {
            state = new DeviceState();
            devices.put(deviceId, state);
        }

        state.temperatura = temperatura;
        state.ultimulMesaj = Math.max(state.ultimulMesaj, timestamp);
        state.numarAlarme++;
        state.prag = prag;
    }

    public synchronized void updateStatus(String deviceId, long timestamp, String text) {
        DeviceState state = devices.get(deviceId);

        if (state == null) {
            state = new DeviceState();
            devices.put(deviceId, state);
        }

        state.ultimulMesaj = Math.max(state.ultimulMesaj, timestamp);
        state.ultimulStatus = text;

    }

    /**
     * Afișează un tabel cu starea tuturor device-urilor.
     */
    public synchronized void print() {
        // TODO 5: afișați câte un rând pentru fiecare device, de exemplu:
        //   DEVICE     TEMP    ULTIMUL MESAJ   ALARME
        //   device-1   23.41   acum 1 s        2
        System.out.println("DEVICE\tTEMP\tULTIMUL MESAJ\tALARME");

        long now = System.currentTimeMillis();

        for (Map.Entry<String, DeviceState> entry : devices.entrySet()) {
            String deviceId = entry.getKey();
            DeviceState state = entry.getValue();

            String temperatura = state.temperatura == null
                    ? "-"
                    : String.format(Locale.US, "%.2f", state.temperatura);

            long vechimeSecunde = (now - state.ultimulMesaj) / 1000;

            System.out.printf("%s\t%s\tacum %d s\t%d%n",
                    deviceId, temperatura, vechimeSecunde, state.numarAlarme);
        }
    }
}
