package vendor.amlogic.hardware.systemcontrol.V1_0;

/** Host fixture for the exact inherited callback type used by the firmware. */
public interface ISystemControl {
    public interface readSysfsCallback {
        void onValues(int result, String value);
    }
}
