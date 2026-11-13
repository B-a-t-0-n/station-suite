package local.station.voicebridge;

import java.nio.file.Files;
import java.nio.file.Paths;

/** File boundary for synthetic host tests only; the production class has no I/O. */
public final class NativeOggInputHost {
    private NativeOggInputHost() { }
    public static void main(String[] args) throws Exception {
        byte[] input = "null".equals(args[0]) ? null : Files.readAllBytes(Paths.get(args[0]));
        byte[] before = input == null ? null : input.clone();
        try {
            NativeOggInput.Result result = NativeOggInput.normalize(input);
            if (input == result.audio || !java.util.Arrays.equals(input, before))
                throw new AssertionError("normalizer mutated or reused caller input");
            Files.write(Paths.get(args[1]), result.audio);
            System.out.println("OK " + result.segments + " " + result.pages + " " + result.eosRepairs
                    + " " + result.serialRewrites + " " + result.inputBytes + " " + result.outputBytes);
        } catch (NativeOggInput.InputException failure) {
            if (!java.util.Arrays.equals(input, before) || failure.getCause() != null
                    || !failure.code.equals(failure.getMessage()) || !failure.code.matches("[A-Z_]+"))
                throw new AssertionError("unsafe exception or caller input mutation");
            System.out.println("ERROR " + failure.code);
            System.exit(2);
        }
    }
}
