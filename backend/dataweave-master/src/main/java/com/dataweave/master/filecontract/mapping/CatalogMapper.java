package com.dataweave.master.filecontract.mapping;

import com.dataweave.master.domain.CatalogNode;
import com.dataweave.master.filecontract.dto.FolderDoc;
import com.dataweave.master.filecontract.error.FileContractException;
import com.dataweave.master.filecontract.naming.SlugRules;
import com.dataweave.master.filecontract.yaml.DeterministicYaml;

import java.util.*;

/**
 * Maps between directory tree (with {@code _folder.yaml} markers) and domain {@link CatalogNode} list.
 *
 * <p>Catalog identity = directory path relative to project root (FR-007).
 * Parent/child relationship = directory nesting (FR-003).
 * Empty directories are tracked via {@code _folder.yaml} marker files.
 */
public class CatalogMapper {

    private final DeterministicYaml yaml;

    /** Fixed marker filename for catalog directories (FR-003). */
    public static final String FOLDER_MARKER = "_folder.yaml";

    public CatalogMapper(DeterministicYaml yaml) {
        this.yaml = yaml;
    }

    // ---- Serialize (domain → file) ----

    /**
     * Build FolderDoc from CatalogNode.
     */
    public FolderDoc toFolderDoc(CatalogNode node) {
        return new FolderDoc(node.getName(), node.getSortOrder());
    }

    /**
     * Serialize FolderDoc to _folder.yaml content.
     */
    public String serialize(FolderDoc doc) {
        var map = DeterministicYaml.orderedMap();
        DeterministicYaml.put(map, "name", doc.name());
        DeterministicYaml.putIfPresent(map, "sortOrder", doc.sortOrder());
        return yaml.dump(map);
    }

    // ---- Deserialize (file → domain) ----

    /**
     * Parse _folder.yaml content into FolderDoc.
     */
    public FolderDoc fromYaml(String content, String filePath) {
        var raw = yaml.load(content);
        String name = TagMapper.requiredString(raw, "name", filePath);
        Integer sortOrder = TaskMapper.optionalInt(raw, "sortOrder", filePath);
        return new FolderDoc(name, sortOrder);
    }

    /**
     * Convert FolderDoc + directory path to CatalogNode.
     * The node has no id/parentId — those are resolved by C during ingest.
     */
    public CatalogNode toDomain(FolderDoc doc, String dirPath) {
        var node = new CatalogNode();
        node.setName(doc.name());
        node.setSortOrder(doc.sortOrder());
        node.setPath(dirPath); // relative path as identity
        return node;
    }

    // ---- Tree extraction from bundle paths (deserialize) ----

    /**
     * Extract all unique directory paths from a set of file paths in a bundle.
     * Each directory that contains at least one file is discovered.
     * Empty directories (with only _folder.yaml) are also included.
     *
     * @param paths all file paths in the bundle
     * @return sorted set of directory paths ("" = root)
     */
    public SortedSet<String> extractDirectories(Set<String> paths) {
        var dirs = new TreeSet<String>();
        dirs.add(""); // root always exists
        for (var p : paths) {
            var parts = p.split("/");
            var sb = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (i > 0) sb.append('/');
                sb.append(parts[i]);
                dirs.add(sb.toString());
            }
        }
        return dirs;
    }

    /**
     * Build a CatalogNode tree from a set of directory paths and a map of
     * dirPath → FolderDoc (parsed from _folder.yaml).
     * The root node (path="") is included if it has a _folder.yaml.
     */
    public List<CatalogNode> buildTree(SortedSet<String> dirs,
                                        Map<String, FolderDoc> folderDocs,
                                        String projectRoot) {
        var nodes = new ArrayList<CatalogNode>();
        for (var dir : dirs) {
            var doc = folderDocs.get(dir);
            if (doc == null) {
                // Directory without _folder.yaml — skip (not a catalog node)
                // But root must have one (project.yaml alone doesn't make root a catalog)
                continue;
            }
            // Validate directory name slug
            if (!dir.isEmpty()) {
                var dirName = dir.substring(dir.lastIndexOf('/') + 1);
                SlugRules.validateSlug(dirName, "directory name", projectRoot + "/" + dir);
            }
            var node = toDomain(doc, dir);
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * Find the catalog path for a task/workflow file.
     * Returns the directory containing the file ("" = root).
     */
    public static String catalogPath(String filePath) {
        var lastSlash = filePath.lastIndexOf('/');
        return lastSlash >= 0 ? filePath.substring(0, lastSlash) : "";
    }

    /**
     * Build parentId mapping from a flat list of CatalogNodes keyed by path.
     * Returns a map of path → parent path.
     */
    public Map<String, String> buildParentMap(Set<String> paths) {
        var parentMap = new LinkedHashMap<String, String>();
        for (var p : paths) {
            if (p.isEmpty()) {
                parentMap.put(p, null);
            } else {
                var lastSlash = p.lastIndexOf('/');
                var parent = lastSlash >= 0 ? p.substring(0, lastSlash) : "";
                parentMap.put(p, parent);
            }
        }
        return parentMap;
    }

    /**
     * Get the ordering key for a catalog node by its path.
     * Sibling order = sortOrder, then name; depth-first tree traversal.
     */
    public static String orderingKey(String path) {
        // depth-first: sort by path segments
        return path;
    }
}
