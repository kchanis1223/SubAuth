package io.github.kchanis1223.subauth.runtime;

import java.util.Locale;
import java.util.stream.Collectors;

public final class ConversationRenderer {
    private ConversationRenderer() {}

    public static String system(RuntimeRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == RuntimeRole.SYSTEM)
                .map(RuntimeMessage::text)
                .collect(Collectors.joining("\n\n"));
    }

    public static String conversation(RuntimeRequest request) {
        var conversational = request.messages().stream()
                .filter(message -> message.role() != RuntimeRole.SYSTEM)
                .toList();
        if (conversational.size() == 1 && conversational.getFirst().role() == RuntimeRole.USER) {
            return conversational.getFirst().text();
        }
        return conversational.stream()
                .map(message -> "[" + message.role().name().toUpperCase(Locale.ROOT) + "]\n" + message.text())
                .collect(Collectors.joining("\n\n"));
    }
}
