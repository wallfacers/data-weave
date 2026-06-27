package com.dataweave.master.filecontract;

import com.dataweave.master.filecontract.mapping.ProjectMapper;
import com.dataweave.master.filecontract.yaml.DeterministicYaml;
import tools.jackson.databind.ObjectMapper;

/**
 * Facade for the file contract: serialize domain aggregates ↔ in-memory file bundles.
 *
 * <pre>{@code
 * FileContract fc = new FileContract();
 *
 * // Pull side (C): domain aggregate → in-memory file tree
 * ProjectFileBundle bundle = fc.serialize(projectExport);
 *
 * // Push side (C): in-memory file tree → domain aggregate (+ validation warnings)
 * ProjectImport imported = fc.deserialize(bundle);
 * }</pre>
 *
 * <p>This is a pure conversion library — no IO, no Spring, no database.
 * Real disk IO belongs to C (server) and D (CLI).
 */
public class FileContract {

    private final ProjectMapper mapper;

    public FileContract() {
        var yaml = new DeterministicYaml();
        var jsonMapper = new ObjectMapper();
        this.mapper = new ProjectMapper(yaml, jsonMapper);
    }

    /**
     * Serialize a project domain aggregate into an in-memory file bundle.
     * Each entry is (relative path → UTF-8 string content).
     */
    public ProjectFileBundle serialize(ProjectExport export) {
        return mapper.serialize(export);
    }

    /**
     * Deserialize an in-memory file bundle into domain objects.
     * Validation errors become warnings in ProjectImport; fatal errors
     * (missing project.yaml) throw FileContractException.
     */
    public ProjectImport deserialize(ProjectFileBundle bundle) {
        return mapper.deserialize(bundle);
    }
}
