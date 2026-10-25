package adris.altoclef.mixins;

import adris.altoclef.commandsystem.CommandAutoCompleter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Makes altoclef's {@code @} commands auto-complete exactly like vanilla {@code /} commands:
 * the same suggestion popup, tab completion, arrow cycling, click-to-apply and syntax coloring,
 * all driven by the vanilla {@link CommandSuggestions} UI with altoclef's own Brigadier tree.
 */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {

    @Shadow
    @Final
    private Minecraft minecraft;
    @Shadow
    @Final
    private EditBox input;
    @Shadow
    @Final
    private boolean onlyShowIfCursorPastError;
    @Shadow
    private boolean keepSuggestions;
    @Shadow
    private ParseResults<ClientSuggestionProvider> currentParse;
    @Shadow
    private boolean currentParseIsCommand;
    @Shadow
    private boolean currentParseIsMessage;
    @Shadow
    private CompletableFuture<Suggestions> pendingSuggestions;
    @Shadow
    private CommandSuggestions.SuggestionsList suggestions;
    @Shadow
    @Final
    private List<FormattedCharSequence> commandUsage;

    @Invoker("updateUsageInfo")
    public abstract void invokeUpdateUsageInfo(ParseResults<ClientSuggestionProvider> currentParse, Suggestions suggestions);

    @Inject(method = "updateCommandInfo", at = @At("HEAD"), cancellable = true)
    private void altoclefHandleClientCommands(CallbackInfo ci) {
        String command = this.input.getValue();
        String prefix = CommandAutoCompleter.getPrefix();
        if (prefix.isEmpty() || !command.startsWith(prefix)) {
            // Not an altoclef command; leave vanilla handling alone.
            return;
        }
        CommandDispatcher<ClientSuggestionProvider> dispatcher = CommandAutoCompleter.getDispatcher();
        if (dispatcher == null || this.minecraft.player == null || this.minecraft.player.connection == null) {
            return;
        }

        // Mirror the vanilla state handling, but parse with altoclef's own dispatcher.
        if (this.currentParse != null && !this.currentParse.getReader().getString().equals(command)) {
            this.currentParse = null;
            this.currentParseIsCommand = false;
            this.currentParseIsMessage = false;
        }
        if (!this.keepSuggestions) {
            this.input.setSuggestion(null);
            this.suggestions = null;
        }
        this.commandUsage.clear();

        StringReader reader = new StringReader(command);
        int prefixLength = prefix.length();
        // Skip the prefix before parsing, exactly like vanilla skips the "/".
        if (reader.canRead(prefixLength)) {
            reader.setCursor(prefixLength);
        } else {
            reader.setCursor(command.length());
        }
        int cursorPosition = this.input.getCursorPosition();
        ClientSuggestionProvider source = this.minecraft.player.connection.getSuggestionsProvider();
        if (this.currentParse == null) {
            this.currentParse = dispatcher.parse(reader, source);
            // Altoclef commands are client-side only; they must work even on servers
            // that disallow "/" commands, so never flag them as server commands.
            this.currentParseIsCommand = false;
            this.currentParseIsMessage = false;
        }
        int parseStart = this.onlyShowIfCursorPastError ? reader.getCursor() : prefixLength;
        if (!(cursorPosition < parseStart || this.suggestions != null && this.keepSuggestions)) {
            this.pendingSuggestions = dispatcher.getCompletionSuggestions(this.currentParse, cursorPosition);
            this.pendingSuggestions.thenAccept(suggestionResult -> {
                if (!this.pendingSuggestions.isDone()) {
                    return;
                }
                this.invokeUpdateUsageInfo(this.currentParse, suggestionResult);
            });
        }
        ci.cancel();
    }
}
