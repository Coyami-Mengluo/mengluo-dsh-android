package ai.mengluo.dsh.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Reads only the official workspace directory index, never sessions or credentials. */
final class ProjectCatalog {
    final List<ProjectFiles.Project> projects = new ArrayList<>();
    String warning;
    static ProjectCatalog read(File profile) {
        ProjectCatalog result = new ProjectCatalog();
        result.projects.add(new ProjectFiles.Project("默认工作区", "/workspace"));
        try {
            File index = ProjectFiles.withoutLinks(profile, "storages/workspace.json");
            if (!index.exists()) return result;
            byte[] bytes;
            try (InputStream input = new FileInputStream(index)) { bytes = ProjectFiles.boundedBytes(input, 8L * 1024 * 1024); }
            JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONObject workspaces = data.getJSONObject("tables").getJSONObject("workspaces");
            JSONArray ids = data.optJSONObject("global") == null ? null : data.getJSONObject("global").optJSONArray("workspaceIds");
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            if (ids != null) for (int i = 0; i < ids.length(); i++) ordered.add(ids.getString(i));
            else { ArrayList<String> keys = new ArrayList<>(); workspaces.keys().forEachRemaining(keys::add); Collections.sort(keys); ordered.addAll(keys); }
            Set<String> paths = new HashSet<>(Set.of("/workspace"));
            for (String id : ordered) {
                JSONObject row = workspaces.optJSONObject(id);
                if (row == null) { result.warning = "部分项目记录缺失，请在 Harness 中确认后刷新。"; continue; }
                String path;
                try { path = ProjectFiles.guestPath(row.getString("path")); }
                catch (Exception invalid) { result.warning = "部分项目路径无效，已跳过；请在 Harness 中重新选择。"; continue; }
                if (!paths.add(path)) continue;
                String title = row.optString("title", "").replaceAll("[\\p{Cntrl}]", " ").trim();
                if (title.isEmpty()) title = path.substring(path.lastIndexOf('/') + 1);
                if (title.length() > 120) title = title.substring(0, 120);
                result.projects.add(new ProjectFiles.Project(title.isEmpty() ? path : title, path));
            }
        } catch (Exception error) { result.warning = "暂时无法读取 Harness 项目列表，可先使用默认工作区，稍后刷新。"; }
        return result;
    }
}
