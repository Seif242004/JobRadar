package com.jobradar.notifier.internal;

import java.util.List;

/** Talks to one Telegram bot. The production implementation calls the Bot API. */
public interface TelegramClient {

    /**
     * An inline button: either opens {@code url}, or sends {@code callbackData} back to the bot.
     * Callback data is at most 64 bytes.
     */
    record Button(String label, String url, String callbackData) {

        public static Button link(String label, String url) {
            return new Button(label, url, null);
        }

        public static Button action(String label, String callbackData) {
            return new Button(label, null, callbackData);
        }
    }

    /** A tap on a callback button. {@code fromId} is the Telegram user who tapped. */
    record Tap(long updateId, String callbackId, long fromId, long chatId, long messageId, String data) {}

    /** One batch of updates: the taps in it and the offset to ask for next. */
    record Updates(long nextOffset, List<Tap> taps) {}

    /**
     * @param text      plain text, at most 4096 characters
     * @param buttonUrl when not null, a single inline button with {@code buttonLabel} opens it
     */
    void sendMessage(long chatId, String text, String buttonLabel, String buttonUrl);

    /** Same, with rows of buttons under the message. */
    void sendWithButtons(long chatId, String text, List<List<Button>> rows);

    /** Long-polls for button taps; blocks up to {@code timeoutSeconds}. */
    Updates getUpdates(long offset, int timeoutSeconds);

    /** Stops the spinner on the tapped button and shows {@code text} briefly. */
    void answerTap(String callbackId, String text);

    /** Removes the buttons of a message, so an old prompt cannot be tapped twice. */
    void clearButtons(long chatId, long messageId);
}
