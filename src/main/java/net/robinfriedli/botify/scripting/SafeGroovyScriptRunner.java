package net.robinfriedli.botify.scripting;

import java.io.ByteArrayInputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.lang.GroovyShell;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.robinfriedli.botify.Botify;
import net.robinfriedli.botify.command.CommandContext;
import net.robinfriedli.botify.discord.MessageService;
import net.robinfriedli.botify.discord.property.properties.ColorSchemeProperty;
import net.robinfriedli.botify.exceptions.Abort;
import net.robinfriedli.botify.exceptions.CommandRuntimeException;
import net.robinfriedli.botify.exceptions.ExceptionUtils;

public class SafeGroovyScriptRunner {

    private final CommandContext context;
    private final GroovyShell groovyShell;
    private final GroovyWhitelistInterceptor groovyWhitelistInterceptor;

    public SafeGroovyScriptRunner(CommandContext context, GroovyShell groovyShell, GroovyWhitelistInterceptor groovyWhitelistInterceptor) {
        this.context = context;
        this.groovyShell = groovyShell;
        this.groovyWhitelistInterceptor = groovyWhitelistInterceptor;
    }

    @SuppressWarnings("deprecation")
    public Object runWithTimeLimit(String script, long timeout, TimeUnit timeUnit) throws ExecutionException, TimeoutException {
        CompletableFuture<Object> result = new CompletableFuture<>();
        Thread scriptExecutionThread = new Thread(() -> {
            CommandContext.Current.set(context);
            groovyWhitelistInterceptor.register();
            try {
                result.complete(groovyShell.evaluate(script));
            } catch (SecurityException e) {
                result.completeExceptionally(e);
            } catch (ThreadDeath ignored) {
                result.complete(null);
            } catch (Throwable e) {
                if (e instanceof CommandRuntimeException && e.getCause() instanceof ThreadDeath) {
                    result.complete(null);
                    return;
                }

                result.completeExceptionally(e);
            }
        });

        scriptExecutionThread.setName("script-execution-thread-" + context);
        scriptExecutionThread.start();

        try {
            return result.get(timeout, timeUnit);
        } catch (TimeoutException | InterruptedException e) {
            // first interrupt, in case the thread is holding any open connections or similar
            scriptExecutionThread.interrupt();
            try {
                Object o = result.get(10, TimeUnit.SECONDS);

                if (e instanceof TimeoutException) {
                    throw new TimeoutException("Script execution timed out");
                }

                return o;
            } catch (TimeoutException | InterruptedException e1) {
                Logger logger = LoggerFactory.getLogger(getClass());
                logger.warn("Forced script execution thread termination after waiting for an additional 10 seconds after sending interrupt signal");
                // then force stop (e.g if script = `while (true) {}` there is not much else to do)
                scriptExecutionThread.interrupt();
                scriptExecutionThread.stop();
                String message = "Script execution was forced to stop after the script did not respond to interrupt signal";
                throw new TimeoutException(e instanceof TimeoutException ? message + " sent after timeout" : message);
            }
        }
    }

    public void runAndSendResult(String script, long timeout, TimeUnit timeUnit) {
        MessageService messageService = Botify.get().getMessageService();
        MessageChannel channel = context.getChannel();
        try {
            Object result = runWithTimeLimit(script, timeout, timeUnit);
            if (result != null) {
                String resultString = result.toString();
                if (resultString.length() < 1000) {
                    EmbedBuilder embedBuilder = new EmbedBuilder();
                    embedBuilder.setTitle("Output");
                    embedBuilder.setDescription("```" + resultString + "```");
                    messageService.send(embedBuilder, channel);
                } else {
                    EmbedBuilder embedBuilder = new EmbedBuilder().setTitle("Output too long, attaching as file");
                    embedBuilder.setColor(ColorSchemeProperty.getColor());
                    byte[] bytes = resultString.getBytes();

                    if (bytes.length > 1000000) {
                        messageService.sendError("Output too long", channel);
                        return;
                    }

                    ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
                    messageService.executeMessageAction(channel, c -> c.sendMessage(embedBuilder.build()).addFile(inputStream, "output.txt"));
                }
            }
        } catch (TimeoutException e) {
            messageService.sendError(e.getMessage(), channel);
        } catch (ExecutionException e) {
            Throwable error = e.getCause() != null ? e.getCause() : e;

            if (error instanceof SecurityException) {
                messageService.sendError(error.getMessage(), channel);
            } else if (!(error instanceof Abort)) {
                EmbedBuilder embedBuilder = ExceptionUtils.buildErrorEmbed(error);
                embedBuilder.setTitle("Error occurred while executing script");
                messageService.sendTemporary(embedBuilder.build(), channel);
            }
        }
    }

}