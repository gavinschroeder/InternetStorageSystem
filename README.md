## Capital Converter Client/Server (PA1)

This project implements a simple TCP client/server pair for the **Capital Converter** assignment (CIS4930 Internet Storage Systems, Spring 2026).

- **Server**: `server.java`
- **Client**: `client.java`

The server accepts strings from the client, validates that they are all lowercase letters `a`–`z`, converts them to uppercase, and sends them back. Non‑alphabet inputs cause an error message and a retransmission request. Sending `bye` cleanly shuts down both client and server.

### Requirements

- Java 8+ (JDK) installed and on your `PATH`
- Two machines on the same network (or two terminals on one machine for basic testing)

### Compile

From the project root:

```bash
javac server.java client.java
```

This produces `server.class` and `client.class`.

### Run the server

On the **server machine**:

```bash
java server [port_number]
```

Example (replace `1234` with your chosen port, e.g., last 4 digits of UFID, `< 65536`):

```bash
java server 1234
```

The server:

- Listens on the given TCP port.
- Sends `Hello!` when a client connects.
- For each line from the client:
  - If the line is `bye`, replies with `disconnected` and exits.
  - If the line contains any non‑`a`–`z` characters, replies with an error message and asks for retransmission.
  - Otherwise, replies with the capitalized version of the string.

### Run the client

On the **client machine** (after the server is running). On Windows, use PowerShell or Command Prompt; the same `java` commands apply.

```bash
java client [serverURL] [port_number]
```

Examples:

- Remote server: `java client my-server-hostname 1234`
- Same machine (e.g. client on Windows, server on same PC): `java client localhost 1234`

The client:

- Connects to the server, prints the initial `Hello!` message.
- Repeatedly prompts:

  ```text
  Enter a lowercase string (or 'bye' to quit):
  ```

- For each input:
  - Starts a timer, sends the string, waits for response, and stops the timer.
  - If you entered `bye`, it prints the server’s `disconnected` message, prints `exit`, and terminates.
  - If the server responds with an error (non‑alphabet input), the client prints the error and asks for a new string (RTT not counted).
  - For valid lowercase inputs, it prints the capitalized response and records the round‑trip time (RTT) in milliseconds.

At the end (after `bye`), the client prints RTT statistics over all successful runs:

- Minimum
- Mean
- Maximum
- Standard deviation

For the assignment, make sure you collect **at least 5** successful RTT measurements.

