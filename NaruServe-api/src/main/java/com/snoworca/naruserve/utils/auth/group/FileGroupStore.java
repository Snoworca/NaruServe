package com.snoworca.naruserve.utils.auth.group;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.auth.internal.AtomicFiles;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups in one file, rewritten atomically on every change (write a temporary file, sync, rename) and read back on
 * construction, so memberships and edges survive a restart. Thread-safe within one process; one file has one
 * writer. The file starts with the line {@code naru-groups 1}. The file is read within a size limit (8 MB by
 * default, {@link #DEFAULT_MAX_FILE_BYTES}) compared with its length before anything is read, so a huge or corrupt file
 * fails construction with a message starting with {@code FAIL} instead of exhausting the heap, and a change that would
 * grow the file above the limit is refused with an {@link IOException} (memory keeps the previous state), so the file
 * can always be read back. Not shared between nodes, so {@code Deployment.MULTI_INSTANCE} refuses it.
 */
// @req IR-IAM-004
public final class FileGroupStore implements GroupAdminStore {

    static final String HEADER = "naru-groups 1";

    /** The default size limit of the file: 8 MB. */
    public static final long DEFAULT_MAX_FILE_BYTES = 8L * 1024 * 1024;

    private final File file;
    private final GroupLimits limits;
    private final long maxFileBytes;
    private GroupGraph graph;

    /** A store over {@code file}, created on the first write when absent; a corrupt file fails here. */
    // @req IR-IAM-004
    public FileGroupStore(File file) throws IOException {
        this(file, GroupLimits.defaults(), new ConsoleLogger());
    }

    /** A store over {@code file} whose edge check uses {@code limits}; recovery warnings go to {@code logger}. */
    // @req IR-IAM-004
    public FileGroupStore(File file, GroupLimits limits, Logger logger) throws IOException {
        this(file, limits, DEFAULT_MAX_FILE_BYTES, logger);
    }

    /**
     * A store over {@code file} whose edge check uses {@code limits}, read and written within {@code maxFileBytes}
     * (at least 1); recovery warnings go to {@code logger}.
     */
    // @req IR-IAM-004
    // @req REL-IAM-004
    public FileGroupStore(File file, GroupLimits limits, long maxFileBytes, Logger logger) throws IOException {
        if (maxFileBytes < 1) {
            throw new IllegalArgumentException("maxFileBytes must be at least 1: " + maxFileBytes);
        }
        this.file = file;
        this.limits = limits;
        this.maxFileBytes = maxFileBytes;
        GroupGraph loaded = AtomicFiles.load(file, maxFileBytes, this::tooLarge, FileGroupStore::parse, logger);
        this.graph = loaded == null ? new GroupGraph() : loaded;
    }

    private String tooLarge(long length) {
        return "the groups file has " + length + " bytes, above the limit of " + maxFileBytes + " bytes; raise it with "
                + "new FileGroupStore(file, limits, maxFileBytes, logger), or move to an app-provided GroupAdminStore "
                + "backed by the application's database";
    }

    /** The file this store writes. */
    public File getFile() {
        return file;
    }

    static GroupGraph parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        if (!lines[0].startsWith("naru-groups ")) {
            throw new IllegalArgumentException("not a naru-groups file");
        }
        if (!lines[0].equals(HEADER)) {
            throw new IllegalArgumentException("unknown naru-groups format version " + lines[0].substring(12)
                    + "; this release reads version 1");
        }
        GroupGraph g = new GroupGraph();
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            if (l.isEmpty()) {
                continue;
            }
            String rest = l.substring(2);
            int tab = rest.indexOf('\t');
            switch (l.charAt(0)) {
                case 'g':
                    g.create(rest);
                    break;
                case 'm':
                    g.addMember(rest.substring(0, tab), unescape(rest.substring(tab + 1)));
                    break;
                case 'e':
                    String child = rest.substring(0, tab);
                    String parent = rest.substring(tab + 1);
                    g.link(child, parent);
                    break;
                case 'r':
                    g.grantRole(rest.substring(0, tab), unescape(rest.substring(tab + 1)));
                    break;
                default:
                    throw new IllegalArgumentException("unknown line " + (i + 1));
            }
        }
        return g;
    }

    private byte[] render() {
        StringBuilder b = new StringBuilder(HEADER).append('\n');
        for (String group : graph.members.keySet()) {
            b.append("g ").append(group).append('\n');
        }
        for (Map.Entry<String, Set<String>> e : graph.members.entrySet()) {
            for (String user : e.getValue()) {
                b.append("m ").append(e.getKey()).append('\t').append(escape(user)).append('\n');
            }
        }
        for (Map.Entry<String, Set<String>> e : graph.parents.entrySet()) {
            for (String parent : e.getValue()) {
                b.append("e ").append(e.getKey()).append('\t').append(parent).append('\n');
            }
        }
        for (Map.Entry<String, Set<String>> e : graph.roles.entrySet()) {
            for (String role : e.getValue()) {
                b.append("r ").append(e.getKey()).append('\t').append(escape(role)).append('\n');
            }
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** One change of the graph. */
    private interface Change {
        void apply(GroupGraph g);
    }

    /** Applies {@code change} and writes the file; when the write fails the graph is restored, so memory never holds
     * a change the disk lacks. */
    private void change(Change change) throws IOException {
        byte[] before = render();
        change.apply(graph);
        try {
            byte[] content = render();
            if (content.length > maxFileBytes) {
                throw new IOException("the groups file would grow to " + content.length + " bytes, above the limit of "
                        + maxFileBytes + " bytes, so the change is refused; raise it with new FileGroupStore(file, "
                        + "limits, maxFileBytes, logger), or move to an app-provided GroupAdminStore");
            }
            AtomicFiles.write(file, content);
        } catch (IOException e) {
            graph = parse(before);
            throw e;
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    @Override
    public synchronized Set<String> groupsOfUser(String userId) {
        return graph.groupsOfUser(userId);
    }

    @Override
    public synchronized Set<String> parentsOf(Set<String> groupIds) {
        return graph.parentsOf(groupIds);
    }

    @Override
    public synchronized boolean createGroup(String groupId) throws IOException {
        if (graph.exists(GroupGraph.checkId(groupId))) {
            return false;
        }
        change(g -> g.create(groupId));
        return true;
    }

    @Override
    public synchronized boolean deleteGroup(String groupId) throws IOException {
        if (!graph.exists(groupId)) {
            return false;
        }
        change(g -> g.delete(groupId));
        return true;
    }

    @Override
    public synchronized List<String> listGroups() {
        return graph.list();
    }

    @Override
    public synchronized boolean groupExists(String groupId) {
        return graph.exists(groupId);
    }

    @Override
    public synchronized void addMember(String groupId, String userId) throws IOException {
        if (!graph.membersOf(groupId).contains(userId)) {
            change(g -> g.addMember(groupId, userId));
        }
    }

    @Override
    public synchronized void removeMember(String groupId, String userId) throws IOException {
        if (graph.membersOf(groupId).contains(userId)) {
            change(g -> g.removeMember(groupId, userId));
        }
    }

    @Override
    public synchronized Set<String> membersOf(String groupId) {
        return graph.membersOf(groupId);
    }

    /** Checks cycle and depth and inserts the edge under this store's lock; a refused edge is a 409 AuthRejection. */
    // @req IR-IAM-004
    @Override
    public synchronized void addParent(String childId, String parentId) throws IOException {
        if (!graph.parentsOf(java.util.Collections.singleton(childId)).contains(parentId)) {
            change(g -> g.addParent(childId, parentId, limits));
        }
    }

    @Override
    public synchronized void removeParent(String childId, String parentId) throws IOException {
        if (graph.parentsOf(java.util.Collections.singleton(childId)).contains(parentId)) {
            change(g -> g.removeParent(childId, parentId));
        }
    }

    @Override
    public synchronized Set<String> childrenOf(String groupId) {
        return graph.childrenOf(groupId);
    }

    @Override
    public synchronized void removeUser(String userId) throws IOException {
        if (!graph.groupsOfUser(userId).isEmpty()) {
            change(g -> g.removeUser(userId));
        }
    }

    /**
     * Gives {@code role} to the group and writes the file, so its members, direct or through child groups, get the
     * role's scopes; a second grant is a no-op. An unknown group or an empty role, or one holding a tab, CR or LF,
     * throws {@link IllegalArgumentException}; a failed write throws {@link IOException} and changes nothing. A grant
     * after start reaches a signed-in user when its cached principal expires.
     */
    // @req FR-IAM-016
    public synchronized void grantRole(String groupId, String role) throws IOException {
        GroupGraph.checkRole(role);
        if (!graph.exists(groupId)) {
            throw new com.snoworca.naruserve.utils.auth.internal.AuthArgumentException("no group " + groupId);
        }
        if (!graph.rolesOf(groupId).contains(role)) {
            change(g -> g.grantRole(groupId, role));
        }
    }

    /** Takes {@code role} back from the group and writes the file; a no-op when it does not hold it. */
    // @req FR-IAM-016
    public synchronized void revokeRole(String groupId, String role) throws IOException {
        if (graph.rolesOf(groupId).contains(role)) {
            change(g -> g.revokeRole(groupId, role));
        }
    }

    /** The roles the group itself holds (not those of its parents); empty when none. */
    // @req FR-IAM-016
    public synchronized Set<String> rolesOf(String groupId) {
        return graph.rolesOf(groupId);
    }

    /** The union of the roles the given groups hold. */
    // @req FR-IAM-016
    @Override
    public synchronized Set<String> rolesOfGroups(Set<String> groupIds) {
        return graph.rolesOfGroups(groupIds);
    }

    @Override
    public synchronized String toString() {
        return "FileGroupStore{file=" + file + ", groups=" + graph.members.size() + "}";
    }
}
