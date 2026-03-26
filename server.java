/*
 * server.java
 * CIS4930 - Internet Storage Systems, Spring 2026
 * PA2: File Transfer Server
 *
 * Protocol (text lines + binary):
 * - After accept: server sends "Hello!"
 * - Client sends a filename (line). If it exists in STORAGE_FOLDER:
 *     server sends "SENDING <bytes>" (line) then streams exactly <bytes> raw bytes.
 *   else:
 *     server sends "File not found" (line)
 * - If client sends "bye":
 *     server sends "disconnected" (line) and exits
 */

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class server {

    private static final String GREETING_MESSAGE = "Hello!";
    private static final String DISCONNECT_COMMAND = "bye";
    private static final String DISCONNECT_RESPONSE = "disconnected";
    private static final String FILE_NOT_FOUND = "File not found";

    // Folder where files are stored on server
    private static final String STORAGE_FOLDER = "server_files";

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

        boolean shouldShutdown = false;
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port + "...");

            while (!shouldShutdown) {
                try (Socket clientSocket = serverSocket.accept()) {
                    System.out.println("Client connected from " + clientSocket.getInetAddress());

                    try (
                            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                            OutputStream rawOut = new BufferedOutputStream(clientSocket.getOutputStream())
                    ) {
                        sendLine(rawOut, GREETING_MESSAGE);

                        String inputLine;
                        while ((inputLine = in.readLine()) != null) {
                            inputLine = inputLine.trim();
                            System.out.println("Received from client: " + inputLine);

                            if (DISCONNECT_COMMAND.equals(inputLine)) {
                                sendLine(rawOut, DISCONNECT_RESPONSE);
                                shouldShutdown = true;
                                break;
                            }

                            File requestedFile = resolveSafeFile(storageDir, inputLine);
                            if (requestedFile == null || !requestedFile.exists() || !requestedFile.isFile()) {
                                System.out.println("File not found: " + new File(storageDir, inputLine).getAbsolutePath());
                                sendLine(rawOut, FILE_NOT_FOUND);
                                continue;
                            }

                            try {
                                long fileSize = requestedFile.length();
                                System.out.println("Sending file: " + requestedFile.getName() + " (" + fileSize + " bytes)");

                                sendLine(rawOut, "SENDING " + fileSize);
                                sendFileBytes(rawOut, requestedFile);

                                System.out.println("File sent successfully: " + requestedFile.getName());
                            } catch (IOException e) {
                                System.err.println("Error sending file: " + e.getMessage());
                                sendLine(rawOut, "ERROR: " + e.getMessage());
                            } catch (Exception e) {
                                System.err.println("Unexpected error: " + e.getMessage());
                                sendLine(rawOut, "ERROR: " + e.getMessage());
                            }
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Error while communicating with client: " + e.getMessage());
                }
            }

            System.out.println("Server shutting down.");
        } catch (IOException e) {
            System.err.println("Could not listen on port: " + e.getMessage());
        }
    }

    private static void sendLine(OutputStream out, String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        out.write(bytes);
        out.flush();
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

    /**
     * Prevents requests like "..\\..\\secret" from escaping STORAGE_FOLDER.
     * Returns null if the resolved path is outside the storage directory.
     */
    private static File resolveSafeFile(File storageDir, String requestedName) {
        try {
            // Only allow simple filenames (no subdirectories) to avoid ambiguity and traversal tricks.
            if (requestedName.contains("/") || requestedName.contains("\\") || requestedName.contains("..")) {
                return null;
            }

            File candidate = new File(storageDir, requestedName);
            if (!candidate.exists()) {
                File ci = findCaseInsensitive(storageDir, requestedName);
                if (ci != null) {
                    candidate = ci;
                }
            }
            String base = storageDir.getCanonicalPath() + File.separator;
            String target = candidate.getCanonicalPath();
            if (!target.startsWith(base)) {
                return null;
            }
            return candidate;
        } catch (IOException e) {
            return null;
        }
    }

    private static File findCaseInsensitive(File storageDir, String requestedName) {
        File[] files = storageDir.listFiles();
        if (files == null) {
            return null;
        }
        for (File f : files) {
            if (f != null && f.isFile() && f.getName().equalsIgnoreCase(requestedName)) {
                return f;
            }
        }
        return null;
    }
}

