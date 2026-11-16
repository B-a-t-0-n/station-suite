import java.util.Arrays;
import vendor.amlogic.hardware.systemcontrol.V1_1.ISystemControl;

/** Runs the unmodified production main; shutdown hook captures failures using System.exit. */
public final class BackendHarness {
    public static void main(String[] args) {
        ISystemControl.configure(args[0], args[1]);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                System.out.println("TRACE " + String.join("|", ISystemControl.calls));
                System.out.println("STATE " + ISystemControl.value);
            }
        }));
        if ("round-trip".equals(args[2])) {
            RingBrightnessBackend.main(new String[] {"dim", args[1]});
            RingBrightnessBackend.main(new String[] {"restore", args[1]});
            RingBrightnessBackend.main(new String[] {"restore", args[1]});
        } else {
            RingBrightnessBackend.main(Arrays.copyOfRange(args, 2, args.length));
        }
    }
}
