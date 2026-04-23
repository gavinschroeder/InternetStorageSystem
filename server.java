
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class server {

    //protocol constants
    private static final String GREETING        = "Hello!";
    private static final String DISCONNECT_CMD  = "bye";
    private static final String DISCONNECT_RESP = "disconnected";
    //returned to client for any command other than SEND or bye
    private static final String ERR_UNKNOWN_CMD = "Please type a different command";
    private static final int    TOTAL_FILES     = 10;
    //directory on server that holds 10 image files
    private static final String STORAGE_FOLDER = "server_files";
    private static final int FILES_PER_BATCH = 10;

    private static final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private static final AtomicInteger activeClients = new AtomicInteger(0);
    private static volatile ServerSocket serverSocketRef;

    //entry point
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
            System.exit(1);
            return;
        }

        //ensurestorage directory exists
        File storageDir = new File(STORAGE_FOLDER);
        if (!storageDir.exists()) {
            storageDir.mkdirs();
            System.out.println("[Server] Created storage folder: " + storageDir.getAbsolutePath());
        }
        System.out.println("[Server] Serving files from: " + storageDir.getAbsolutePath());

        //create a new thread per client and reuses idle ones
        ExecutorService executor = Executors.newCachedThreadPool();

        //main accept loop
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[Server] Listening on port " + port + " ...");

            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("[Server] New client: " + clientSocket.getInetAddress());
                    //spawn dedicated ClientHandler thread, terminates automatically when run returns
                    executor.submit(new ClientHandler(clientSocket, storageDir));
                } catch (SocketException e) {
                    if (shutdownRequested.get()) {
                        break;
                    }
                    System.err.println("Socket error while accepting connection: " + e.getMessage());
                } catch (IOException e) {
                    System.err.println("[Server] Accept error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[Server] Cannot bind to port " + port + ": " + e.getMessage());
        } finally {
            //graceful executor shutdown so transfers can finish
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

    //clienthandler
    //handles one client connection inside its own thread.
    private static class ClientHandler implements Runnable {

        private final Socket socket;
        private final File   storageDir;

        ClientHandler(Socket socket, File storageDir) {
            this.socket     = socket;
            this.storageDir = storageDir;
        }

        @Override
        public void run() {
            try (
                //raw streams to interleave text headers and binary file data
                InputStream  rawIn  = new BufferedInputStream(socket.getInputStream());
                OutputStream rawOut = socket.getOutputStream()
            ) {
                //greeting
                sendLine(rawOut, GREETING);

                //command loop reads one command per iteration
                String line;
                while ((line = readLine(rawIn)) != null) {
                    line = line.trim();
                    System.out.println("[" + socket.getInetAddress() + "] CMD: " + line);

                    //graceful disconnect
                    if (DISCONNECT_CMD.equalsIgnoreCase(line)) {
                        sendLine(rawOut, DISCONNECT_RESP);
                        break;
                    }

                    //batchsize controls how many files are grouped into each BATCH_START/BATCH_END envelope sent over the wire
                    if (line.toUpperCase().startsWith("SEND")) {
                        int batchSize = TOTAL_FILES; // default, send all at once
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            try {
                                batchSize = Integer.parseInt(parts[1]);
                                if (batchSize < 1) batchSize = 1;
                            } catch (NumberFormatException e) {
                                sendLine(rawOut, "ERROR: Invalid batch size. Expected: SEND <batchSize>");
                                continue;
                            }
                        }

                        //immediately read client's SEQ line
                        //random permutation of 1-10 generated by the client
                        String seqLine = readLine(rawIn);
                        List<Integer> sequence = null;
                        if (seqLine != null) {
                            seqLine = seqLine.trim();
                            if (seqLine.toUpperCase().startsWith("SEQ")) {
                                sequence = parseSequence(seqLine.substring(3).trim());
                            }
                        }

                        //load files and stream them, catch any I/O or system exception
                        try {
                            processBatchSend(sequence, batchSize, rawOut);
                        } catch (IOException e) {
                            System.err.println("[" + socket.getInetAddress() + "] Batch error: " + e.getMessage());
                            try { sendLine(rawOut, "ERROR: " + e.getMessage()); } catch (IOException ignore) {}
                        } catch (Exception e) {
                            System.err.println("[" + socket.getInetAddress() + "] Unexpected error: " + e.getMessage());
                            try { sendLine(rawOut, "ERROR: " + e.getMessage()); } catch (IOException ignore) {}
                        }
                        continue;
                    }

                    //unknown command
                    System.out.println("[" + socket.getInetAddress() + "] Unknown command: " + line);
                    sendLine(rawOut, ERR_UNKNOWN_CMD);
                }

                    List<Integer> order = parseSequenceLine(seqLine.substring(4).trim());
                    if (order == null) {
                        sendLine(rawOut, "ERROR: invalid sequence; expected numbers 1..10");
                        continue;
                    }

                    processBatchSend(order, batchSize, rawOut);
                }
            } catch (IOException e) {
                System.err.println("[ClientHandler] I/O error for " + socket.getInetAddress()
                        + ": " + e.getMessage());
            } finally {
                //always close socket, thread exits automatically
                try { socket.close(); } catch (IOException ignore) {}
                System.out.println("[Server] Closed connection: " + socket.getInetAddress());
            }
        }

        //loads TOTAL_FILES files from storageDir, orders them according to the client-supplied sequence 
        private void processBatchSend(List<Integer> sequence, int batchSize,
                                      OutputStream rawOut) throws IOException {
            //collect only regular files from storage directory
            File[] allFiles = storageDir.listFiles(File::isFile);
            if (allFiles == null || allFiles.length < TOTAL_FILES) {
                sendLine(rawOut, "ERROR: Server needs at least " + TOTAL_FILES
                        + " files in '" + STORAGE_FOLDER + "'. Found: "
                        + (allFiles == null ? 0 : allFiles.length));
                return;
            }

            //sort alphabetically so index pool is deterministic across clients and runs
            Arrays.sort(allFiles, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            List<File> pool = Arrays.asList(Arrays.copyOf(allFiles, TOTAL_FILES));

            //validate client sequence, fall back to fresh random permutation if invalid
            if (sequence == null || sequence.size() != TOTAL_FILES) {
                System.out.println("[" + socket.getInetAddress()
                        + "] Invalid/missing SEQ – using server-side random order.");
                sequence = new ArrayList<>();
                for (int i = 1; i <= TOTAL_FILES; i++) sequence.add(i);
                Collections.shuffle(sequence, new Random());
            }

            //map 1-based client indices to actual file objects
            List<File> orderedFiles = new ArrayList<>();
            for (int idx : sequence) {
                if (idx < 1 || idx > TOTAL_FILES) {
                    sendLine(rawOut, "ERROR: Sequence index out of range: " + idx);
                    return;
                }
                orderedFiles.add(pool.get(idx - 1));
            }

            //stream files in sub-batches
            int total = orderedFiles.size();
            int sent  = 0;
            while (sent < total) {
                int count = Math.min(batchSize, total - sent);

                //announce sub-batch so client knows exactly how many FILE headers follow
                sendLine(rawOut, "BATCH_START " + count);

                for (int j = 0; j < count; j++) {
                    File f        = orderedFiles.get(sent + j);
                    long fileSize = f.length();
                    System.out.println("[" + socket.getInetAddress() + "] Sending: "
                            + f.getName() + " (" + fileSize + " bytes)");
                    //header carries filename and exact byte count for length-delimited receive
                    sendLine(rawOut, "FILE " + f.getName() + " " + fileSize);
                    sendFileBytes(rawOut, f); //raw bytes immediately follow the header
                }
                sent += count;
            }

            //signal that entire batch transfer is complete
            sendLine(rawOut, "BATCH_END");
            System.out.println("[" + socket.getInetAddress()
                    + "] Batch send complete – " + total + " files.");
        }
    }

        //parse sequence of integers, return null on failure
        private List<Integer> parseSequence(String s) {
            if (s == null || s.isEmpty()) return null;
            String[] parts = s.split("[,\\s]+");
            List<Integer> result = new ArrayList<>();
            try {
                for (String p : parts) {
                    if (!p.isEmpty()) result.add(Integer.parseInt(p));
                }
            } catch (IOException ignore) {
            }
            return result.isEmpty() ? null : result;
        }
    }

    //shared I/O helpers 
    //UTF-8 text line terminated by '\n' and flush immediately
    static void sendLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    //read \n-terminated line from a raw InputStream without consuming past newline
    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) return sb.length() == 0 ? null : sb.toString(); // EOF
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        return sb.toString();
    }

    //stream raw file bytes into `out` using a large buffer
    static void sendFileBytes(OutputStream out, File file) throws IOException {
        byte[] buffer = new byte[65536];
        try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
            int read;
            while ((read = fis.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }
}

