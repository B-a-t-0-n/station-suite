package local.station.voicebridge;

/** One reusable local model; speech and lifecycle operations belong to its worker. */
public interface OnDeviceRecognizer extends AutoCloseable {
    Session newSession() throws Exception;
    long loadMillis();
    @Override void close();
    interface Session extends AutoCloseable {
        void acceptPcm16(byte[] data, int offset, int count) throws Exception;
        String finish() throws Exception;
        void cancel();
        long pcmBytes();
        long decodeMillis();
        /** Passive worker-only observation; never finalizes, resets, or dispatches a command. */
        default boolean endpointReady() { return false; }
        /**
         * Worker-only bounded hypothesis for transport speech acknowledgement.
         * It may change with later input and must never authorize a control action.
         */
        default String previewText() { return ""; }
        @Override void close();
    }
}
