package mibsdk.runtime;

import java.util.Collections;
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
    private final List<String> icons;

    PluginManifest(String id, String name, String version, String author, int api, String main,
            List<String> permissions, List<String> dependencies) {
        this(id, name, version, author, api, main, permissions, dependencies, Collections.<String>emptyList());
    }

    PluginManifest(String id, String name, String version, String author, int api, String main,
            List<String> permissions, List<String> dependencies, List<String> icons) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.author = author;
        this.api = api;
        this.main = main;
        this.permissions = permissions;
        this.dependencies = dependencies;
        this.icons = icons;
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

    /** The icon names the package declares ({@code "icons"}, api 3); empty if none. */
    public List<String> icons() {
        return icons;
    }
}
