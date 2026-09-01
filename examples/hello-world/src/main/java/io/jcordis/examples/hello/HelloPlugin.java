package io.jcordis.examples.hello;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;

/** Minimal plugin: logs on load and unload. */
public class HelloPlugin implements Plugin {

    @Override
    public Object apply(Context ctx, Object config) {
        ctx.logger("hello").info("hello world from jcordis");
        return (Disposable) () -> ctx.logger("hello").info("goodbye");
    }

    public static void main(String[] args) {
        Context root = Context.create();
        new io.jcordis.core.logger.ConsoleExporter(root);
        io.jcordis.loader.Loader loader = new io.jcordis.loader.Loader(root);
        loader.builtin("hello", new HelloPlugin());

        io.jcordis.loader.EntryOptions greet = new io.jcordis.loader.EntryOptions();
        greet.id = "greet";
        greet.name = "hello";
        loader.read(java.util.List.of(greet));

        System.out.println("--- unloading the plugin ---");
        loader.remove("greet");
    }
}
