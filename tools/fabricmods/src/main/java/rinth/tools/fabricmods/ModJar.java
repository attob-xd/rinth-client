package rinth.tools.fabricmods;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One Fabric mod as its jar declares it ({@code fabric.mod.json}) and every file in it: a jar from
 * mods/, or one nested in another (jar-in-jar).
 *
 * @param environment {@code client}, {@code server} or {@code *}
 * @param depends mod id to its version requirements, as written
 * @param entrypoints key ({@code main}, {@code client}...) to the declarations under it
 * @param unsupportedEntrypoints declarations the build can't make (another language adapter)
 * @param mixinConfigs the configs that apply on the client: those for any side and for the client
 * @param accessWidener the access widener's path in the jar, or null
 * @param source where the jar came from, for the report
 * @param entries every file in the jar by path, nested jars included as bytes
 */
record ModJar(
        String id,
        String version,
        String name,
        String description,
        String environment,
        List<String> provides,
        List<String> authors,
        List<String> licenses,
        Map<String, List<String>> depends,
        Map<String, List<String>> entrypoints,
        List<String> unsupportedEntrypoints,
        List<String> mixinConfigs,
        @Nullable String accessWidener,
        String source,
        Map<String, byte[]> entries,
        String icon) {

    boolean clientSide() {
        return !environment.equals("server");
    }
}
