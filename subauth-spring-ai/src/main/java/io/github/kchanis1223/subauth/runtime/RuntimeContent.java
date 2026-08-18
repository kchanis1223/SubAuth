package io.github.kchanis1223.subauth.runtime;

public sealed interface RuntimeContent {
    record Text(String text) implements RuntimeContent {
        public Text {
            if (text == null) throw new IllegalArgumentException("text is required");
        }
    }

    record Media(String mimeType, byte[] data, String id, String name) implements RuntimeContent {
        public Media {
            if (mimeType == null || mimeType.isBlank()) {
                throw new IllegalArgumentException("mimeType is required");
            }
            if (data == null) throw new IllegalArgumentException("media data is required");
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    record ToolCall(String id, String type, String name, String arguments) implements RuntimeContent {
        public ToolCall {
            if (id == null) id = "";
            if (type == null) type = "function";
            if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name is required");
            if (arguments == null) arguments = "";
        }
    }

    record ToolResult(String id, String name, String responseData) implements RuntimeContent {
        public ToolResult {
            if (id == null) id = "";
            if (name == null) name = "";
            if (responseData == null) responseData = "";
        }
    }
}
