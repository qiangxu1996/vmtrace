# Rules for generating parsers using ANTLR3.

load(":functions.bzl", "create_option_file")

def _antlr_srcjar_impl(ctx):
    src_jar = ctx.outputs.src_jar
    option_file = create_option_file(
        ctx,
        src_jar.path + ".src",
        "\n".join([src.path for src in ctx.files.srcs]),
    )
    args = ["-o", src_jar.path, "@" + option_file.path]

    # Invoke our custom AntlrCompiler.
    ctx.actions.run(
        inputs = ctx.files.srcs + [option_file],
        outputs = [src_jar],
        mnemonic = "antlr",
        arguments = args,
        executable = ctx.executable._antlr,
    )

# Provides a srcjar with the sources generated by ANTLR. The rule should be used as part of the
# srcs in a java_library.
antlr_sources = rule(
    attrs = {
        "srcs": attr.label_list(
            non_empty = True,
            allow_files = True,
        ),
        "_antlr": attr.label(
            executable = True,
            cfg = "host",
            default = Label("//tools/base/bazel:antlr"),
            allow_files = True,
        ),
    },
    outputs = {
        "src_jar": "%{name}.srcjar",
    },
    implementation = _antlr_srcjar_impl,
)
