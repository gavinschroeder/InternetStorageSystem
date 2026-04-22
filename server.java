/*
 * server.java
 * CIS4930 - Internet Storage Systems, Spring 2026
 * PA3: Concurrent Batched File Transfer Server
 */

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class server {

    private static final String GREETING_MESSAGE = "Hello!";
    private static final String DISCONNECT_COMMAND = "bye";
    private static final String DISCONNECT_RESPONSE = "disconnected";
    private static final String INVALID_COMMAND_MESSAGE = "Please type a different command";
    private static final String STORAGE_FOLDER = "server_files";
    private static final int FILES_PER_BATCH = 10;

    private static final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private static final AtomicInteger activeClients = new AtomicInteger(0);
    private static volatile ServerSocket serverSocketRef;

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java server [port_number]");
            System.exit(1);
        }

        int port;
        try {
            port = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("Port number must be an integer.");
            return;
        }

        File storageDir = new File(STORAGE_FOLDER);
        if (!storageDir.exists()) {
            storageDir.mkdirs();
            System.out.println("Created storage folder: " + storageDir.getAbsolutePath());
        }
        System.out.println("Serving files from: " + storageDir.getAbsolutePath());

        ExecutorService executor = Executors.newCachedThreadPool();
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocketRef = serverSocket;
            System.out.println("Server listening on port " + port + "...");

            while (!shutdownRequested.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    activeClients.incrementAndGet();
                    System.out.println("Client connected from " + clientSocket.getInetAddress());
                    executor.submit(new ClientHandler(clientSocket, storageDir));
                } catch (SocketException e) {
                    if (shutdownRequested.get()) {
                        break;
                    }
                    System.err.println("Socket error while accepting connection: " + e.getMessage());
                } catch (IOException e) {
                    if (!shutdownRequested.get()) {
                        System.err.println("Error while accepting client connection: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Could not listen on port: " + e.getMessage());
        } finally {
            shutdownRequested.set(true);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            System.out.println("Server shutting down.");
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private final File storageDir;

        ClientHandler(Socket socket, File storageDir) {
            this.socket = socket;
            this.storageDir = storageDir;
        }

        @Override
        public void run() {
            try (
                    InputStream rawIn = new BufferedInputStream(socket.getInputStream());
                    OutputStream rawOut = new BufferedOutputStream(socket.getOutputStream())
            ) {
                sendLine(rawOut, GREETING_MESSAGE);

                String commandLine;
                while ((commandLine = readLine(rawIn)) != null) {
                    commandLine = commandLine.trim();
                    if (commandLine.isEmpty()) {
                        sendLine(rawOut, INVALID_COMMAND_MESSAGE);
                        continue;
                    }

                    if (DISCONNECT_COMMAND.equalsIgnoreCase(commandLine)) {
                        sendLine(rawOut, DISCONNECT_RESPONSE);
                        requestServerShutdownIfLastClient();
                        break;
                    }

                    if (!commandLine.toUpperCase().startsWith("SEND")) {
                        sendLine(rawOut, INVALID_COMMAND_MESSAGE);
                        continue;
                    }

                    int batchSize = parseBatchSize(commandLine);
                    if (batchSize < 1 || batchSize > 3) {
                        sendLine(rawOut, "ERROR: batch size must be 1, 2, or 3");
                        continue;
                    }

                    String seqLine = readLine(rawIn);
                    if (seqLine == null || !seqLine.toUpperCase().startsWith("SEQ ")) {
                        sendLine(rawOut, "ERROR: expected SEQ line after SEND");
                        continue;
                    }

                    List<Integer> order = parseSequenceLine(seqLine.substring(4).trim());
                    if (order == null) {
                        sendLine(rawOut, "ERROR: invalid sequence; expected numbers 1..10");
                        continue;
                    }

                    processBatchSend(order, batchSize, rawOut);
                }
            } catch (IOException e) {
                System.err.println("Error while communicating with client: " + e.getMessage());
            } finally {
                try {
                    socket.close();
                } catch (IOException ignore) {
                }
                int remaining = activeClients.decrementAndGet();
                System.out.println("Connection closed: " + socket.getInetAddress() + " (active clients: " + remaining + ")");
            }
        }

        private int parseBatchSize(String sendLine) {
            String[] parts = sendLine.trim().split("\\s+");
            if (parts.length < 2) {
                return 1;
            }
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private void processBatchSend(List<Integer> order, int batchSize, OutputStream rawOut) throws IOException {
            List<File> pool = loadFilePool(storageDir);
            if (pool.size() < FILES_PER_BATCH) {
                sendLine(rawOut, "ERROR: need at least 10 files in server_files");
                return;
            }

            sendLine(rawOut, "BATCH_BEGIN " + batchSize);
            for (int round = 1; round <= batchSize; round++) {
                for (int idx : order) {
                    File f = pool.get(idx);
                    long fileSize = f.length();
                    sendLine(rawOut, "FILE " + f.getName() + " " + fileSize);
                    sendFileBytes(rawOut, f);
                }
            }
            sendLine(rawOut, "BATCH_END");
        }

        private List<Integer> parseSequenceLine(String s) {
            String[] parts = s.split("[, ]+");
            if (parts.length != FILES_PER_BATCH) {
                return null;
            }

            boolean[] seen = new boolean[FILES_PER_BATCH + 1];
            List<Integer> order = new ArrayList<>();
            try {
                for (String p : parts) {
                    int v = Integer.parseInt(p.trim());
                    if (v < 1 || v > FILES_PER_BATCH || seen[v]) {
                        return null;
                    }
                    seen[v] = true;
                    order.add(v - 1);
                }
            } catch (NumberFormatException e) {
                return null;
            }
            return order;
        }
    }

    private static void requestServerShutdownIfLastClient() {
        if (activeClients.get() == 1 && shutdownRequested.compareAndSet(false, true)) {
            try {
                if (serverSocketRef != null && !serverSocketRef.isClosed()) {
                    serverSocketRef.close();
                }
            } catch (IOException ignore) {
            }
        }
    }

    private static List<File> loadFilePool(File storageDir) {
        File[] allFiles = storageDir.listFiles();
        List<File> candidates = new ArrayList<>();
        if (allFiles == null) {
            return candidates;
        }
        for (File f : allFiles) {
            if (f != null && f.isFile()) {
                candidates.add(f);
            }
        }
        Collections.sort(candidates, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        if (candidates.size() > FILES_PER_BATCH) {
            return new ArrayList<>(candidates.subList(0, FILES_PER_BATCH));
        }
        return candidates;
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

    private static void sendFileBytes(OutputStream out, File file) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
            while ((bytesRead = fis.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        }
    }
}

