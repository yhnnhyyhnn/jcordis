package demo.plugin;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;

/** Sample jcordis plugin: logs on load and unload. */
public final class SamplePlugin implements Plugin {

    @Override
    public Object apply(Context ctx, Object config) {
        ctx.logger("sample").info("sample plugin loaded");
        return (Disposable) () -> ctx.logger("sample").info("sample plugin unloaded");
    }
}
