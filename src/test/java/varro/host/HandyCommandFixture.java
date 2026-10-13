package varro.host;

/** Isolated subprocess fixture. Tests never invoke an installed Handy application. */
public final class HandyCommandFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "echo" -> System.out.print(args[1]);
            case "sleep" -> Thread.sleep(10_000);
            case "output" -> System.out.write(new byte[65_537]);
            case "exit" -> System.exit(3);
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
