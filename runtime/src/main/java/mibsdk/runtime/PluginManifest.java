package mibsdk.runtime;

import java.util.List;

/** Parsed and validated plugin.json. */
public final class PluginManifest {

    private final String id;
    private final String name;
    private final String version;
    private final String author;
    private final int api;
    private final String main;
    private final List<String> permissions;
    private final List<String> dependencies;

    PluginManifest(String id, String name, String version, String author, int api, String main,
            List<String> permissions, List<String> dependencies) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.author = author;
        this.api = api;
        this.main = main;
        this.permissions = permissions;
        this.dependencies = dependencies;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public String author() {
        return author;
    }

    public int api() {
        return api;
    }

    public String main() {
        return main;
    }

    public List<String> permissions() {
        return permissions;
    }

    public List<String> dependencies() {
        return dependencies;
    }
}
