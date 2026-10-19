package dev.chatsorter;

import net.minecraft.text.Text;
import net.minecraft.text.MutableText;

/** One routed message plus the metadata ChatSorter needs for history and copy actions. */
public final class ChatHistoryEntry {
    public final Text message;
    public final String time;
    public final ChatSorter.Tab tab;
    public final String sender;
    public final String technical;
    public String componentJson = "";
    public int repeats;
    public boolean restored;

    public ChatHistoryEntry(Text message, String time, ChatSorter.Tab tab, String sender,
                            String technical, int repeats, boolean restored) {
        this.message = message;
        this.time = time;
        this.tab = tab;
        this.sender = sender == null ? "" : sender;
        this.technical = technical == null ? "" : technical;
        this.repeats = Math.max(1, repeats);
        this.restored = restored;
    }

    public Text displayText() {
        MutableText shown = restored
            ? Text.literal("[пред. сессия] ").styled(s -> s.withColor(0xFF8A929B)).append(message.copy())
            : message.copy();
        if (repeats > 1) shown.append(Text.literal(" ×" + repeats).styled(s -> s.withColor(0xFF9EA7B0)));
        return shown;
    }
}
