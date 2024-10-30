package org.example;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.FileUpload;
import okhttp3.OkHttpClient;

import javax.net.ssl.*;
import java.io.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main extends ListenerAdapter {
    private Process terminalProcess;
    private BufferedReader terminalReader;
    private BufferedWriter terminalWriter;
    private boolean inTerminalSession = false;
    private static final long ALLOWED_USER_ID = 1301118861634174976L;
    private StringBuilder outputBuffer = new StringBuilder();
    private ScheduledExecutorService scheduler;

    public static void main(String[] args) throws Exception {
        // Set up SSL context to bypass certificate validation
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];  // Return empty array instead of null
                    }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
        };

        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new SecureRandom());
        SSLContext.setDefault(sc);

        // Configure OkHttpClient to use the custom SSL context
        OkHttpClient.Builder httpClientBuilder = new OkHttpClient.Builder();
        httpClientBuilder.sslSocketFactory(sc.getSocketFactory(), (X509TrustManager) trustAllCerts[0]);

        // Initialize the JDA bot instance with your bot token and bypassed SSL verification
        JDABuilder.createDefault("MTMwMTExOTM3ODI5MjczNjAzMQ.GRaHRz.d3E31DmusI4VqES4nN9w8Pf6YxW1lH4nwowpK0")
                .setHttpClientBuilder(httpClientBuilder)
                .setActivity(Activity.playing("something"))
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new Main())
                .build();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Ignore messages from bots to avoid loops
        if (event.getAuthor().isBot()) return;

        String message = event.getMessage().getContentRaw();

        // Check if the message is !enter
        if (message.equalsIgnoreCase("!enter") && event.getAuthor().getIdLong() == ALLOWED_USER_ID) {
            startTerminal(event.getChannel());
        }
        // Check if the message is !exit
        else if (message.equalsIgnoreCase("!exit") && inTerminalSession) {
            stopTerminal();
            event.getChannel().sendMessage("Exited terminal session.").queue();
        }
        // Handle input for the terminal session
        else if (inTerminalSession) {
            sendToTerminal(message, event.getChannel());
        }
    }

    private void startTerminal(MessageChannel channel) {
        String os = System.getProperty("os.name").toLowerCase();
        ProcessBuilder processBuilder;
        if (os.contains("win")) {
            processBuilder = new ProcessBuilder("cmd.exe", "/K");
        } else {
            processBuilder = new ProcessBuilder("bash");
        }

        processBuilder.redirectErrorStream(true);
        try {
            terminalProcess = processBuilder.start();

            InputStreamReader inputReader;
            if (os.contains("win")) {
                inputReader = new InputStreamReader(terminalProcess.getInputStream(), "windows-1252");
            } else {
                inputReader = new InputStreamReader(terminalProcess.getInputStream());
            }

            terminalReader = new BufferedReader(inputReader);
            terminalWriter = new BufferedWriter(new OutputStreamWriter(terminalProcess.getOutputStream()));
            inTerminalSession = true;

            channel.sendMessage("Terminal session started. Type `!exit` to leave.").queue();

            // Start a thread to continuously read output from the terminal
            new Thread(() -> {
                try {
                    String line;
                    while ((line = terminalReader.readLine()) != null) {
                        outputBuffer.append(line).append("\n");
                        System.out.println(line); // Output to server console

                        // If the buffer exceeds 1900 characters, send it as a text file
                        if (outputBuffer.length() > 1900) {
                            File tempFile = File.createTempFile("terminal_output", ".txt");
                            try (FileWriter fw = new FileWriter(tempFile)) {
                                fw.write(outputBuffer.toString());
                            }
                            channel.sendMessage("Terminal output exceeds 1900 characters, sending as a text file:").queue();
                            channel.sendFiles(FileUpload.fromData(tempFile, "terminal_output.txt")).queue();
                            outputBuffer.setLength(0);
                        }
                    }
                } catch (IOException e) {
                    channel.sendMessage("Error reading from terminal: " + e.getMessage()).queue();
                    System.out.println("Error reading from terminal: " + e.getMessage());
                }
            }).start();

            // Start a scheduled task to send the output buffer to the channel
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(() -> {
                if (outputBuffer.length() > 0) {
                    channel.sendMessage("```\n" + outputBuffer.toString().trim() + "\n```").queue();
                    outputBuffer.setLength(0);
                }
            }, 2, 2, TimeUnit.SECONDS);

        } catch (IOException e) {
            channel.sendMessage("Error starting terminal: " + e.getMessage()).queue();
            System.out.println("Error starting terminal: " + e.getMessage());
        }
    }

    private void sendToTerminal(String command, MessageChannel channel) {
        try {
            terminalWriter.write(command);
            terminalWriter.newLine();
            terminalWriter.flush();
        } catch (IOException e) {
            channel.sendMessage("Error sending command to terminal: " + e.getMessage()).queue();
            System.out.println("Error sending command to terminal: " + e.getMessage());
        }
    }

    private void stopTerminal() {
        try {
            if (terminalProcess != null) {
                terminalProcess.destroy();
            }
            inTerminalSession = false;
            scheduler.shutdownNow();
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error stopping terminal: " + e.getMessage());
        }
    }
}