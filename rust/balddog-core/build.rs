fn main() {
    let rcheevos_dir = std::path::Path::new("third_party/rcheevos");
    println!("cargo:rerun-if-changed=third_party/rcheevos");

    let c_files = [
        "src/runtime/rc_alloc.c",
        "src/runtime/rc_condition.c",
        "src/runtime/rc_condset.c",
        "src/runtime/rc_consoleinfo.c",
        "src/runtime/rc_format.c",
        "src/runtime/rc_lboard.c",
        "src/runtime/rc_memref.c",
        "src/runtime/rc_operand.c",
        "src/runtime/rc_richpresence.c",
        "src/runtime/rc_runtime.c",
        "src/runtime/rc_runtime_progress.c",
        "src/runtime/rc_trigger.c",
        "src/runtime/rc_typed_value.c",
        "src/runtime/rc_validate.c",
        "src/runtime/rc_value.c",
        "src/util/md5.c",
        "src/util/rc_compat.c",
        "src/util/rc_util.c",
        "src/util/rc_version.c",
    ];

    let mut build = cc::Build::new();
    build
        .include(rcheevos_dir.join("include"))
        .include(rcheevos_dir.join("src"))
        .define("RC_DISABLE_LUA", None)
        .warnings(false);

    for rel in c_files {
        build.file(rcheevos_dir.join(rel));
    }

    build.compile("rcheevos_runtime");
}
