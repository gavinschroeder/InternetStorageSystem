/*
 * client.java
 * CIS4930 - Internet Storage Systems, Spring 2026
 * PA3: Concurrent Batched File Transfer Client
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

public class client {

    private static final String DISCONNECT_COMMAND = "bye";
    private static final String DISCONNECT_RESPONSE = "disconnected";
    private static final String INVALID_COMMAND_MESSAGE = "Please type a different command";
    private static final int MIN_RUNS_FOR_STATS = 5;
    private static final int FILES_PER_BATCH = 10;
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

        Map<Integer, List<Double>> rttByBatchSize = new HashMap<>();
        rttByBatchSize.put(1, new ArrayList<>());
        rttByBatchSize.put(2, new ArrayList<>());
        rttByBatchSize.put(3, new ArrayList<>());

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

            while (true) {
                System.out.print("Enter command (SEND or 'bye'): ");
                String userInput = scanner.nextLine().trim();

                if (DISCONNECT_COMMAND.equalsIgnoreCase(userInput)) {
                    sendLine(rawOut, DISCONNECT_COMMAND);
                    String response = readLine(rawIn);
                    if (response != null) {
                        System.out.println(response);
                    }
                    System.out.println("exit");
                    break;
                }

                int batchSize;
                if ("SEND".equalsIgnoreCase(userInput)) {
                    System.out.print("Enter batch size (1, 2, or 3): ");
                    String batchSizeText = scanner.nextLine().trim();
                    try {
                        batchSize = Integer.parseInt(batchSizeText);
                    } catch (NumberFormatException e) {
                        System.out.println("Batch size must be 1, 2, or 3.");
                        continue;
                    }
                    if (batchSize < 1 || batchSize > 3) {
                        System.out.println("Batch size must be 1, 2, or 3.");
                        continue;
                    }
                } else {
                    sendLine(rawOut, userInput);
                    String response = readLine(rawIn);
                    if (response == null) {
                        System.out.println("Server closed the connection.");
                        break;
                    }
                    System.out.println(response);
                    continue;
                }

                List<Integer> sequence = randomSequence1To10();
                String seqLine = toSequenceLine(sequence);

                long startTime = System.nanoTime();
                sendLine(rawOut, "SEND " + batchSize);
                sendLine(rawOut, "SEQ " + seqLine);

                String firstResponse = readLine(rawIn);
                if (firstResponse == null) {
                    System.out.println("Server closed the connection.");
                    break;
                }
                if (firstResponse.startsWith("ERROR:") || INVALID_COMMAND_MESSAGE.equals(firstResponse)) {
                    System.out.println(firstResponse);
                    continue;
                }
                if (!firstResponse.startsWith("BATCH_BEGIN ")) {
                    System.out.println("Unexpected server response: " + firstResponse);
                    continue;
                }

                int expectedRounds;
                try {
                    expectedRounds = Integer.parseInt(firstResponse.substring("BATCH_BEGIN ".length()).trim());
                } catch (NumberFormatException e) {
                    System.out.println("Invalid BATCH_BEGIN response: " + firstResponse);
                    continue;
                }

                boolean transferFailed = false;
                for (int round = 1; round <= expectedRounds; round++) {
                    for (int i = 0; i < FILES_PER_BATCH; i++) {
                        String fileHeader = readLine(rawIn);
                        if (fileHeader == null) {
                            System.out.println("Connection closed while waiting for file header.");
                            transferFailed = true;
                            break;
                        }

                        FileHeader parsed = parseFileHeader(fileHeader);
                        if (parsed == null) {
                            System.out.println("Unexpected file header: " + fileHeader);
                            transferFailed = true;
                            break;
                        }

                        String localName = "b" + batchSize + "_r" + round + "_" + parsed.fileName;
                        File outFile = new File(downloadDir, safeLocalFileName(localName));
                        try {
                            receiveToFile(rawIn, outFile, parsed.fileSize);
                        } catch (IOException e) {
                            System.out.println("ERROR: " + e.getMessage());
                            transferFailed = true;
                            break;
                        }
                    }
                    if (transferFailed) {
                        break;
                    }
                }

                if (transferFailed) {
                    continue;
                }

                String endMarker = readLine(rawIn);
                if (!"BATCH_END".equals(endMarker)) {
                    System.out.println("Unexpected end marker: " + endMarker);
                    continue;
                }

                long endTime = System.nanoTime();
                double rttMillis = (endTime - startTime) / 1_000_000.0;
                List<Double> statsList = rttByBatchSize.get(batchSize);
                statsList.add(rttMillis);

                System.out.println("Round-trip time (batch size " + batchSize + "): " + formatDouble(rttMillis) + " ms");

                if (statsList.size() % MIN_RUNS_FOR_STATS == 0) {
                    System.out.println();
                    System.out.println("Statistics for batch size " + batchSize + " after " + statsList.size() + " runs:");
                    printStatistics(statsList);
                    System.out.println();
                }
            }

        } catch (UnknownHostException e) {
            System.err.println("Unknown host " + hostName + ": " + e.getMessage());
        } catch (IOException e) {
            System.err.println("I/O error while communicating with " + hostName + ": " + e.getMessage());
        }
    }

    private static List<Integer> randomSequence1To10() {
        List<Integer> seq = new ArrayList<>();
        for (int i = 1; i <= FILES_PER_BATCH; i++) {
            seq.add(i);
        }
        Collections.shuffle(seq);
        return seq;
    }

    private static String toSequenceLine(List<Integer> sequence) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sequence.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(sequence.get(i));
        }
        return sb.toString();
    }

    private static FileHeader parseFileHeader(String header) {
        if (header == null || !header.startsWith("FILE ")) {
            return null;
        }
        String[] parts = header.split("\\s+", 3);
        if (parts.length != 3) {
            return null;
        }
        try {
            long size = Long.parseLong(parts[2]);
            return new FileHeader(parts[1], size);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void sendLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

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
        String name = requestedName.replace("\\", "_").replace("/", "_");
        return name.isBlank() ? "downloaded_file" : name;
    }

    private static void printStatistics(List<Double> rtts) {
        if (rtts.isEmpty()) {
            System.out.println("No data.");
            return;
        }
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
        double stdDev = Math.sqrt(varianceSum / rtts.size());

        System.out.println("Minimum: " + formatDouble(min));
        System.out.println("Mean   : " + formatDouble(mean));
        System.out.println("Maximum: " + formatDouble(max));
        System.out.println("Std Dev: " + formatDouble(stdDev));
    }

    private static String formatDouble(double value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private static class FileHeader {
        private final String fileName;
        private final long fileSize;

        private FileHeader(String fileName, long fileSize) {
            this.fileName = fileName;
            this.fileSize = fileSize;
        }
    }
}

