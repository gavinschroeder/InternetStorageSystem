
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class client {

    //protocol constants
    private static final String DISCONNECT_CMD = "bye";

    //folder where received files are saved on client machine
    private static final String DOWNLOAD_FOLDER = "client_files";

    //total files server holds 
    private static final int TOTAL_FILES = 10;

    //stats are autoprinted after successful trials
    private static final int STATS_INTERVAL = 5;

    //entry point
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java client [serverURL] [port_number]");
            System.exit(1);
        }

        String host = args[0];
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("Port number must be an integer.");
            System.exit(1);
            return;
        }

        //create local download directory for received files
        File downloadDir = new File(DOWNLOAD_FOLDER);
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        System.out.println("Saving received files to: " + downloadDir.getAbsolutePath());

        //server connection and main command loop
        try (
            Socket       socket  = new Socket(host, port);
            InputStream  rawIn   = new BufferedInputStream(socket.getInputStream());
            OutputStream rawOut  = socket.getOutputStream();
            Scanner      scanner = new Scanner(System.in)
        ) {
            //receive and print "Hello!"
            String greeting = readLine(rawIn);
            if (greeting != null) {
                System.out.println(greeting);
            }

            //RTT lists keyed by batch size (1, 2, ..)
            //each entry accumulates across repeated SEND calls
            Map<Integer, List<Double>> rttsByBatch = new HashMap<>();

            //main loop
            while (true) {
                System.out.print("Enter command: ");
                String input = scanner.nextLine().trim();

                if (input.isEmpty()) {
                    continue;
                }

                //client sends "bye", server replies "disconnected"
                //client prints both then prints "exit" and exits
                if (DISCONNECT_CMD.equalsIgnoreCase(input)) {
                    sendLine(rawOut, DISCONNECT_CMD);
                    String resp = readLine(rawIn);
                    if (resp != null) {
                        System.out.println(resp);
                    }
                    System.out.println("exit");
                    break;
                }

                //user sends "SEND", batch size can be provided as an optional argument
                if (input.toUpperCase().startsWith("SEND")) {
                    int batchSize = 1; // default batch size
                    String[] parts = input.split("\\s+");
                    if (parts.length >= 2) {
                        try {
                            batchSize = Integer.parseInt(parts[1]);
                            if (batchSize < 1) {
                                System.out.println("Batch size must be >= 1. Try again.");
                                continue;
                            }
                        } catch (NumberFormatException e) {
                            System.out.println("Invalid batch size. Usage: SEND <batchSize>");
                            continue;
                        }
                    }

                    //RRT function
                    double rtt = performTransfer(rawIn, rawOut, batchSize, downloadDir);

                    if (rtt >= 0) {
                        //print RTT immediately after each round trip
                        System.out.printf("Round-trip time: %.3f ms%n", rtt);

                        //accumulate RTT for this batch size
                        rttsByBatch.computeIfAbsent(batchSize, k -> new ArrayList<>()).add(rtt);

                        //autoprint stats after every successful trial
                        List<Double> rtts = rttsByBatch.get(batchSize);
                        if (rtts.size() % STATS_INTERVAL == 0) {
                            printStatistics(rtts, batchSize);
                        }
                    }
                    continue;
                }

                //unknown command
                //server replies "Please type a different command"
                //client prints server's error message and re-prompts
                sendLine(rawOut, input);
                String serverResp = readLine(rawIn);
                if (serverResp != null) {
                    System.out.println(serverResp);
                }
            }

        } catch (UnknownHostException e) {
            System.err.println("Unknown host: " + host);
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
        }
    }

    //one complete transfer of all TOTAL_FILES files
    private static double performTransfer(InputStream rawIn, OutputStream rawOut, int batchSize, File downloadDir) throws IOException {
        //generate random permutation of 1..TOTAL_FILES
        List<Integer> sequence = new ArrayList<>();
        for (int i = 1; i <= TOTAL_FILES; i++) sequence.add(i);
        Collections.shuffle(sequence, new Random());

        //build SEQ n1 n2 ... n10
        StringBuilder seqBuilder = new StringBuilder("SEQ");
        for (int idx : sequence) seqBuilder.append(' ').append(idx);

        //start timer
        long startMs = System.currentTimeMillis();

        //send SEND and SEQ to server
        sendLine(rawOut, "SEND " + batchSize);
        sendLine(rawOut, seqBuilder.toString());

        //receive and save each file
        int filesReceived = 0;
        try {
            loop:
            while (true) {
                String header = readLine(rawIn);
                if (header == null) {
                    System.out.println("Server closed connection unexpectedly.");
                    return -1;
                }
                header = header.trim();

                //server groups files into sub-batches
                if (header.startsWith("BATCH_START")) {
                    String[] parts = header.split("\\s+");
                    int count;
                    try {
                        count = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                        System.out.println("Malformed BATCH_START header: " + header);
                        return -1;
                    }

                    //receive `count` files in sub-batch
                    for (int i = 0; i < count; i++) {
                        String fileHeader = readLine(rawIn);
                        if (fileHeader == null || !fileHeader.startsWith("FILE ")) {
                            System.out.println("Unexpected file header: " + fileHeader);
                            return -1;
                        }
                        //parse file and size
                        String[] fp = fileHeader.split("\\s+");
                        if (fp.length < 3) {
                            System.out.println("Malformed FILE header: " + fileHeader);
                            return -1;
                        }
                        String fileName = fp[1];
                        long fileSize;
                        try {
                            fileSize = Long.parseLong(fp[2]);
                        } catch (NumberFormatException e) {
                            System.out.println("Invalid file size in header: " + fileHeader);
                            return -1;
                        }

                        //save received file to the download directory
                        File outFile = new File(downloadDir, sanitizeFileName(fileName));
                        receiveToFile(rawIn, outFile, fileSize);
                        filesReceived++;
                        System.out.println("Received: " + outFile.getName()
                                + " (" + fileSize + " bytes)");
                    }

                } else if ("BATCH_END".equals(header)) {
                    //all 10 files have been delivered, exit receive loop
                    break loop;

                } else if (header.startsWith("ERROR")) {
                    //server reported a problem, print and abort
                    System.out.println(header);
                    return -1;

                } else {
                    System.out.println("Unexpected server message: " + header);
                    return -1;
                }
            }
        } catch (IOException e) {
            System.out.println("Error receiving files: " + e.getMessage());
            return -1;
        }

        //stop timer, compute and return RTT
        long endMs = System.currentTimeMillis();
        double rttMs = (double) (endMs - startMs);

        System.out.println("Received " + filesReceived + "/" + TOTAL_FILES + " files.");
        return rttMs;
    }

    //computes min, mean, max, and standard deviation from the accumulated RTT list
    private static void printStatistics(List<Double> rtts, int batchSize) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double sum = 0.0;

        for (double rtt : rtts) {
            if (rtt < min) min = rtt;
            if (rtt > max) max = rtt;
            sum += rtt;
        }
        double mean = sum / rtts.size();

        //standard deviation
        double varSum = 0.0;
        for (double rtt : rtts) {
            double diff = rtt - mean;
            varSum += diff * diff;
        }
        double stdDev = Math.sqrt(varSum / rtts.size());

        System.out.println();
        System.out.println("=== RTT Statistics | Batch Size: " + batchSize
                + " | Trials: " + rtts.size() + " ===");
        System.out.printf("  Minimum : %.3f ms%n", min);
        System.out.printf("  Mean    : %.3f ms%n", mean);
        System.out.printf("  Maximum : %.3f ms%n", max);
        System.out.printf("  Std Dev : %.3f ms%n", stdDev);
        System.out.println("=================================================");
        System.out.println();
    }

    //I/O helpers
    //UTF-8 text line terminated by '\n' and flush immediately
    private static void sendLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // Read a \n-terminated line byte-by-byte
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) return sb.length() == 0 ? null : sb.toString(); // EOF
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        return sb.toString();
    }

    //receive size bytes from the stream and write them to outFile
    private static void receiveToFile(InputStream in, File outFile, long size) throws IOException {
        if (size < 0) throw new IOException("Invalid file size: " + size);
        byte[] buffer    = new byte[65536];
        long   remaining = size;
        try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(outFile))) {
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read   = in.read(buffer, 0, toRead);
                if (read == -1) {
                    throw new IOException(
                            "Connection closed before file fully received. "
                            + remaining + " bytes still expected.");
                }
                fos.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    //strip path separators and characters that are illegal in filenames
    private static String sanitizeFileName(String name) {
        return name.replaceAll("[/\\\\:*?\"<>|]", "_");
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

