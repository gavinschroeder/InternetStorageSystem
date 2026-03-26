/*
 * client.java
 * CIS4930 - Internet Storage Systems, Spring 2026
 * PA2: File Transfer Client
 *
 * Connects to the server, prints "Hello!", then repeatedly sends a file name.
 * If the server replies "SENDING <bytes>", the client receives exactly <bytes>
 * bytes and saves the file to a predetermined folder. Measures round-trip time
 * (RTT) for successful transfers and prints min/mean/max/stddev on exit.
 *
 * Termination: user types "bye" -> client sends it, receives "disconnected",
 * prints "exit", and exits.
 */

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

public class client {

    private static final String DISCONNECT_COMMAND = "bye";
    private static final String DISCONNECT_RESPONSE = "disconnected";
    private static final String FILE_NOT_FOUND = "File not found";
    private static final String ERROR_PREFIX = "ERROR:";
    private static final int MIN_RUNS_FOR_STATS = 5;

    // Folder where received files are stored on client
    private static final String DOWNLOAD_FOLDER = "client_files";

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java client [serverURL] [port_number]");
            System.exit(1);
        }

        String hostName = args[0];
        int portNumber;

        try {
            portNumber = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("Port number must be an integer.");
            return;
        }

        File downloadDir = new File(DOWNLOAD_FOLDER);
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        System.out.println("Saving received files to: " + downloadDir.getAbsolutePath());

        try (
                Socket socket = new Socket(hostName, portNumber);
                InputStream rawIn = new BufferedInputStream(socket.getInputStream());
                OutputStream rawOut = socket.getOutputStream();
                Scanner scanner = new Scanner(System.in)
        ) {
            String greeting = readLine(rawIn);
            if (greeting != null) {
                System.out.println(greeting);
            }

            List<Double> rtts = new ArrayList<>();

            while (true) {
                System.out.print("Enter a file name (or 'bye' to quit): ");
                String userInput = scanner.nextLine().trim();

                long startTime = System.nanoTime();
                sendLine(rawOut, userInput);

                String responseLine = readLine(rawIn);
                if (responseLine == null) {
                    System.out.println("Server closed the connection.");
                    break;
                }

                if (DISCONNECT_COMMAND.equals(userInput)) {
                    System.out.println(responseLine);
                    System.out.println("exit");
                    break;
                }

                if (FILE_NOT_FOUND.equals(responseLine)) {
                    System.out.println(responseLine);
                    continue;
                }

                if (responseLine.startsWith(ERROR_PREFIX)) {
                    System.out.println(responseLine);
                    continue;
                }

                if (!responseLine.startsWith("SENDING ")) {
                    System.out.println("Unexpected server response: " + responseLine);
                    continue;
                }

                long byteCount;
                try {
                    byteCount = Long.parseLong(responseLine.substring("SENDING ".length()).trim());
                } catch (NumberFormatException e) {
                    System.out.println("Invalid SENDING header: " + responseLine);
                    continue;
                }

                File outFile = new File(downloadDir, safeLocalFileName(userInput));
                try {
                    receiveToFile(rawIn, outFile, byteCount);
                } catch (IOException e) {
                    System.out.println("ERROR: " + e.getMessage());
                    continue;
                }

                long endTime = System.nanoTime();
                double rttMillis = (endTime - startTime) / 1_000_000.0;
                rtts.add(rttMillis);

                System.out.println("Saved " + byteCount + " bytes to: " + outFile.getAbsolutePath());
                System.out.println("RTT (ms): " + formatDouble(rttMillis));
            }

            if (rtts.size() >= MIN_RUNS_FOR_STATS) {
                printStatistics(rtts);
            } else if (!rtts.isEmpty()) {
                System.out.println();
                System.out.println("Collected " + rtts.size() + " successful run(s).");
                System.out.println("For PA2, run at least " + MIN_RUNS_FOR_STATS + " successful transfers to report min/mean/max/stddev.");
            } else {
                System.out.println("No successful round-trip measurements were recorded.");
            }

        } catch (UnknownHostException e) {
            System.err.println("Unknown host " + hostName + ": " + e.getMessage());
        } catch (IOException e) {
            System.err.println("I/O error while communicating with " + hostName + ": " + e.getMessage());
        }
    }

    private static void sendLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Reads a line terminated by '\n' from a raw InputStream without buffering past the newline.
     * Returns null on EOF with no bytes read.
     */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) {
                return sb.length() == 0 ? null : sb.toString();
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                sb.append((char) b);
            }
        }
        return sb.toString();
    }

    private static void receiveToFile(InputStream in, File outFile, long byteCount) throws IOException {
        if (byteCount < 0) {
            throw new IOException("Invalid byte count: " + byteCount);
        }

        byte[] buffer = new byte[8192];
        long remaining = byteCount;

        try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(outFile))) {
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, toRead);
                if (read == -1) {
                    throw new IOException("Connection closed while receiving file. Remaining bytes: " + remaining);
                }
                fos.write(buffer, 0, read);
                remaining -= read;
            }
            fos.flush();
        }
    }

    private static String safeLocalFileName(String requestedName) {
        String name = requestedName;
        name = name.replace("\\", "_").replace("/", "_");
        if (name.isBlank()) {
            return "downloaded_file";
        }
        return name;
    }

    private static void printStatistics(List<Double> rtts) {
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        double sum = 0.0;

        for (double rtt : rtts) {
            if (rtt < min) {
                min = rtt;
            }
            if (rtt > max) {
                max = rtt;
            }
            sum += rtt;
        }

        double mean = sum / rtts.size();

        double varianceSum = 0.0;
        for (double rtt : rtts) {
            double diff = rtt - mean;
            varianceSum += diff * diff;
        }
        double variance = varianceSum / rtts.size();
        double stdDev = Math.sqrt(variance);

        System.out.println();
        System.out.println("Round-trip time statistics (ms) over " + rtts.size() + " successful runs:");
        System.out.println("Minimum: " + formatDouble(min));
        System.out.println("Mean   : " + formatDouble(mean));
        System.out.println("Maximum: " + formatDouble(max));
        System.out.println("Std Dev: " + formatDouble(stdDev));
    }

    private static String formatDouble(double value) {
        return String.format(Locale.US, "%.3f", value);
    }
}

