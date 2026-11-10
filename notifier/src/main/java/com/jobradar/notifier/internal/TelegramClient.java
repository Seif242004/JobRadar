package com.jobradar.notifier.internal;

/** Sends a message to one Telegram chat. The production implementation calls the Bot API. */
public interface TelegramClient {

    /**
     * @param text      plain text, at most 4096 characters
     * @param buttonUrl when not null, a single inline button with {@code buttonLabel} opens it
     */
    void sendMessage(long chatId, String text, String buttonLabel, String buttonUrl);
}
