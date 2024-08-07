/*
 * Copyright (c) 2024 Osiris-Team.
 * All rights reserved.
 *
 * This software is copyrighted work, licensed under the terms
 * of the MIT-License. Consult the "LICENSE" file for details.
 */
package com.osiris.autoplug.client.network.online.connections;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

import com.osiris.autoplug.client.Server;
import com.osiris.autoplug.client.console.Commands;
import com.osiris.jlib.logger.AL;
import com.osiris.jlib.logger.LogFileWriter;

public class SSHServerConsoleReceive implements Command {

    private static final Set<SSHServerConsoleReceive> activeConnections = ConcurrentHashMap.newKeySet();
    private static final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = -1;

    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback exitCallback;
    private ExecutorService executor;

    @Override
    public void setInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
        this.err = err;
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.exitCallback = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) throws IOException {
        AL.info("SSH session started.");
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("SSHConsoleThread-" + t.getId());
            t.setDaemon(true);
            return t;
        });

        out.write("┌───────────────────────────────────────┐\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("│  Welcome to the AutoPlug SSH Console  │\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("│                                       │\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("│             Programmed by             │\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("│           Discord user _z03           │\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("└───────────────────────────────────────┘\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        activeConnections.add(this);

        executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder commandBuffer = new StringBuilder();
                int cursorPosition = 0;
                int c;
                while ((c = reader.read()) != -1) {
                    switch (c) {
                        case '\r':
                        case '\n':
                            if (commandBuffer.length() > 0) {
                                String userInput = commandBuffer.toString().trim();
                                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                AL.debug(SSHServerConsoleReceive.class, "Received SSH command: " + userInput);
                                commandHistory.add(userInput);
                                historyIndex = commandHistory.size();
                                commandBuffer.setLength(0);
                                cursorPosition = 0;
                                handleUserInput(userInput);
                            }
                            break;
                        case 3: // Ctrl+C
                            exitCallback.onExit(0, "Connection closed by user.");
                            break;
                        case 127: // Backspace
                        case 8: // Backspace
                            if (cursorPosition > 0 && commandBuffer.length() > 0) {
                                commandBuffer.deleteCharAt(cursorPosition - 1);
                                cursorPosition--;
                                redrawLine(out, commandBuffer, cursorPosition);
                            }
                            break;
                        case 27: // Escape sequence
                            reader.mark(2);
                            int next1 = reader.read();
                            int next2 = reader.read();
                            if (next1 == '[') {
                                switch (next2) {
                                    case 'A': // Up arrow
                                        if (historyIndex > 0) {
                                            historyIndex--;
                                            loadFromHistory(commandBuffer, historyIndex);
                                            cursorPosition = commandBuffer.length();
                                            redrawLine(out, commandBuffer, cursorPosition);
                                        }
                                        break;
                                    case 'B': // Down arrow
                                        if (historyIndex < commandHistory.size() - 1) {
                                            historyIndex++;
                                            loadFromHistory(commandBuffer, historyIndex);
                                            cursorPosition = commandBuffer.length();
                                            redrawLine(out, commandBuffer, cursorPosition);
                                        } else if (historyIndex == commandHistory.size() - 1) {
                                            historyIndex++;
                                            commandBuffer.setLength(0);
                                            cursorPosition = 0;
                                            redrawLine(out, commandBuffer, cursorPosition);
                                        }
                                        break;
                                    case 'C': // Right arrow
                                        if (cursorPosition < commandBuffer.length()) {
                                            cursorPosition++;
                                            out.write(c);
                                            out.write(next1);
                                            out.write(next2);
                                            out.flush();
                                        }
                                        break;
                                    case 'D': // Left arrow
                                        if (cursorPosition > 0) {
                                            cursorPosition--;
                                            out.write(c);
                                            out.write(next1);
                                            out.write(next2);
                                            out.flush();
                                        }
                                        break;
                                    default:
                                        reader.reset();
                                        break;
                                }
                            }
                            break;
                        default:
                            commandBuffer.insert(cursorPosition, (char) c);
                            cursorPosition++;
                            redrawLine(out, commandBuffer, cursorPosition);
                            break;
                    }

                }
            } catch (IOException e) {
                AL.warn(e);
            } finally {
                cleanup();
            }
        });
    }

    private void cleanup() {
        activeConnections.remove(this);
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        try {
            if (out != null) out.close();
            if (err != null) err.close();
            if (in != null) in.close();
        } catch (IOException e) {
            AL.warn(e);
        }
        exitCallback.onExit(0);
    }

    private void handleUserInput(String userInput) {
        try {
            boolean isAutoPlugCommand = Commands.execute(userInput);
            if (isAutoPlugCommand) {
                LogFileWriter.writeToLog(userInput); // Log command
            } else {
                if (Server.isRunning()) {
                    Server.submitCommand(userInput);
                } else {
                    AL.warn("Failed to submit command '" + userInput + "' because server is not running! Use '.start' to start the server.");
                }
            }
        } catch (Exception e) {
            AL.warn(e);
        }
    }

    private void redrawLine(OutputStream out, StringBuilder commandBuffer, int cursorPosition) throws IOException {
        out.write("\33[2K\r".getBytes(StandardCharsets.UTF_8)); // Clear the line
        out.write(commandBuffer.toString().getBytes(StandardCharsets.UTF_8)); // Write the command
        out.write(String.format("\33[%dG", cursorPosition + 1).getBytes(StandardCharsets.UTF_8)); // Move the cursor
        out.flush();
    }

    private void loadFromHistory(StringBuilder commandBuffer, int index) {
        commandBuffer.setLength(0);
        commandBuffer.append(commandHistory.get(index));
    }

    public void broadcast(String message) {
        try {
            if (!message.endsWith("\n")) {
                message += "\n";
            }
            out.write(message.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            AL.warn(e);
        }
    }

    public static void broadcastToAll(String message) {
        for (SSHServerConsoleReceive connection : activeConnections) {
            connection.broadcast(message);
        }
    }

    @Override
    public void destroy(ChannelSession channel) {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        activeConnections.remove(this);
    }
}
