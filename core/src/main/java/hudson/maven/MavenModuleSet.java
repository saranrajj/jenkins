package hudson.maven;

import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractItem;
import hudson.model.Descriptor.FormException;
import hudson.model.DirectoryBrowserSupport;
import hudson.model.Hudson;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.JDK;
import hudson.model.LargeText;
import hudson.model.Node;
import hudson.model.Project;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.TopLevelItemDescriptor;
import hudson.remoting.VirtualChannel;
import hudson.scm.NullSCM;
import hudson.scm.SCM;
import hudson.scm.SCMS;
import hudson.util.ByteBuffer;
import hudson.util.CopyOnWriteMap;
import hudson.util.IOException2;
import hudson.util.StreamTaskListener;
import org.apache.maven.embedder.MavenEmbedderException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Group of {@link MavenModule}s.
 *
 * <p>
 * This corresponds to the group of Maven POMs that constitute a single
 * tree of projects. This group serves as the grouping of those related
 * modules.
 *
 * @author Kohsuke Kawaguchi
 */
public class MavenModuleSet extends AbstractItem implements TopLevelItem, ItemGroup<MavenModule> {
    /**
     * All {@link MavenModule}s, keyed by their {@link MavenModule#getModuleName()} module name}s.
     */
    transient /*final*/ Map<ModuleName,MavenModule> modules = new CopyOnWriteMap.Tree<ModuleName,MavenModule>();

    private SCM scm = new NullSCM();

    /**
     * Identifies {@link JDK} to be used.
     * Null if no explicit configuration is required.
     *
     * <p>
     * Can't store {@link JDK} directly because {@link Hudson} and {@link Project}
     * are saved independently.
     *
     * @see Hudson#getJDK(String)
     */
    private String jdk;

    /**
     * True to suspend any new builds in this module set.
     */
    private boolean disabled;

    /**
     * If this project is configured to be only built on a certain node,
     * this value will be set to that node. Empty string to indicate
     * affinity to the master, and null to indicate free-roam.
     */
    private String assignedNode;

    public MavenModuleSet(String name) {
        super(Hudson.getInstance(),name);
    }

    public String getUrlChildPrefix() {
        // seemingly redundant "./" is used to make sure that ':' is not interpreted as the scheme identifier
        return ".";
    }

    public Hudson getParent() {
        return Hudson.getInstance();
    }

    /**
     * If this project is configured to be always built on this node,
     * return that {@link Node}. Otherwise null.
     */
    public Node getAssignedNode() {
        return Hudson.getInstance();
        // TODO
        //if(assignedNode==null)
        //    return null;
        //if(assignedNode.equals(""))
        //    return Hudson.getInstance();
        //return Hudson.getInstance().getSlave(assignedNode);
    }

    public SCM getScm() {
        return scm;
    }

    public void setScm(SCM scm) {
        this.scm = scm;
    }

    public JDK getJDK() {
        return getParent().getJDK(jdk);
    }

    public synchronized void setJDK(JDK jdk) throws IOException {
        this.jdk = jdk.getName();
    }
    
    public Collection<MavenModule> getItems() {
        return modules.values();
    }

    public Collection<MavenModule> getModules() {
        return getItems();
    }

    public MavenModule getItem(String name) {
        return modules.get(ModuleName.fromString(name));
    }

    public MavenModule getModule(String name) {
        return getItem(name);
    }

    public Object getDynamic(String token, StaplerRequest req, StaplerResponse rsp) {
        if(ModuleName.isValid(token))
            return getModule(token);
        return super.getDynamic(token,req,rsp);
    }

    public File getRootDirFor(MavenModule child) {
        return new File(new File(getRootDir(),"modules"),child.getModuleName().toFileSystemName());
    }

    public Collection<MavenModule> getAllJobs() {
        return getItems();
    }

    /**
     * Gets the workspace of this job.
     */
    public FilePath getWorkspace() {
        // TODO: support roaming and etc
        return Hudson.getInstance().getWorkspaceFor(this);
    }

    public void onLoad(ItemGroup<? extends Item> parent, String name) throws IOException {
        super.onLoad(parent, name);

        File modulesDir = new File(getRootDir(),"modules");
        modulesDir.mkdirs(); // make sure it exists

        File[] subdirs = modulesDir.listFiles(new FileFilter() {
            public boolean accept(File child) {
                return child.isDirectory();
            }
        });
        modules = new CopyOnWriteMap.Tree<ModuleName,MavenModule>();
        for (File subdir : subdirs) {
            try {
                MavenModule item = (MavenModule) Items.load(this,subdir);
                modules.put(item.getModuleName(), item);
            } catch (IOException e) {
                e.printStackTrace(); // TODO: logging
            }
        }
    }

    /**
     * Obtains a workspace.
     */
    public boolean checkout(Launcher launcher, TaskListener listener) throws IOException {
        try {
            FilePath workspace = getWorkspace();
            workspace.mkdirs();
            return scm.checkout(launcher, workspace, listener);
        } catch (InterruptedException e) {
            e.printStackTrace(listener.fatalError("SCM check out aborted"));
            return false;
        }
    }

    private transient LargeText parsePomBuffer;
    private transient boolean parsePomSucceeded;

    public static enum PomLinkMode {
        NONE, IN_PROGRESS, ERROR;
    }

    /**
     * Returns how to display 'parsing POM' link.
     */
    public PomLinkMode getPomLinkMode() {
        LargeText buf = parsePomBuffer;
        if(buf==null)   return PomLinkMode.NONE;
        if(!buf.isComplete())   return PomLinkMode.IN_PROGRESS;
        if(!parsePomSucceeded)  return PomLinkMode.ERROR;
        return PomLinkMode.NONE;
    }

    public void parsePOMs() {
        ByteBuffer buf = new ByteBuffer();
        parsePomBuffer = new LargeText(buf,false);
        parsePomSucceeded = false;
        final StreamTaskListener listener = new StreamTaskListener(buf);
        try {
            Launcher launcher = getAssignedNode().createLauncher(listener);
            listener.getLogger().println("Fetching up-to-date workspace");
            if(!checkout(launcher,listener))
                return;

            listener.getLogger().println("Parsing POMs");
            // TODO: this needs to be moved to its own class since MavenModuleSet is not serializable
            List<PomInfo> poms = getWorkspace().act(new FileCallable<List<PomInfo>>() {
                public List<PomInfo> invoke(File ws, VirtualChannel channel) throws IOException {
                    // TODO: this logic needs to be smarter
                    File pom = new File(ws,"pom.xml");

                    try {
                        MavenEmbedder embedder = MavenUtil.createEmbedder(listener);
                        MavenProject mp = embedder.readProject(pom);
                        Map<MavenProject,String> relPath = new HashMap<MavenProject,String>();
                        MavenUtil.resolveModules(embedder,mp,"",relPath);

                        List<PomInfo> infos = new ArrayList<PomInfo>();
                        toPomInfo(mp,relPath,infos);
                        return infos;
                    } catch (MavenEmbedderException e) {
                        // TODO: better error handling needed
                        throw new IOException2(e);
                    } catch (ProjectBuildingException e) {
                        throw new IOException2(e);
                    }
                }

                private void toPomInfo(MavenProject mp, Map<MavenProject,String> relPath, List<PomInfo> infos) {
                    infos.add(new PomInfo(mp,relPath.get(mp)));
                    for (MavenProject child : (List<MavenProject>)mp.getCollectedProjects())
                        toPomInfo(child,relPath,infos);
                }
            });

            synchronized(modules) {
                Map<ModuleName,MavenModule> old = new HashMap<ModuleName, MavenModule>(modules);

                modules.clear();
                for (PomInfo pom : poms) {
                    MavenModule mm = old.get(pom.name);
                    if(mm!=null) {// found an existing matching module
                        mm.reconfigure(pom);
                        modules.put(pom.name,mm);
                    } else {// this looks like a new module
                        mm = new MavenModule(this,pom);
                        modules.put(mm.getModuleName(),mm);
                    }
                    mm.save();
                }

                // remaining modules are no longer active.
                old.keySet().removeAll(modules.keySet());
                for (MavenModule om : old.values())
                    om.disable();
                modules.putAll(old);
            }

            Hudson.getInstance().rebuildDependencyGraph();
            listener.getLogger().println("done");
            parsePomSucceeded = true;

        } catch (IOException e) {
            e.printStackTrace(listener.error("Failed to parse POMs"));
        } catch (InterruptedException e) {
            e.printStackTrace(listener.error("Aborted"));
        } catch (RuntimeException e) {
            // bug in the code.
            e.printStackTrace(listener.error("Processing failed due to a bug in the code. Please report thus to users@hudson.dev.java.net"));
            throw e;
        }
        parsePomBuffer.markAsComplete();
    }

//
//
// Web methods
//
//

    /**
     * Triggers the POM parsing and eventually updates the list of {@link MavenModule}s under
     * this project.
     */
    public void doStartParsePOM(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        new Thread(new Runnable() {
            public void run() {
                parsePOMs();
            }
        }).start();
        rsp.sendRedirect(".");
    }

    /**
     * Handles incremental log output.
     */
    public void doProgressiveParsePOMLog( StaplerRequest req, StaplerResponse rsp) throws IOException {
        if(parsePomBuffer==null)
            rsp.setStatus(HttpServletResponse.SC_OK);
        else
            parsePomBuffer.doProgressText(req,rsp);
    }

    public synchronized void doConfigSubmit(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        if(!Hudson.adminCheck(req,rsp))
            return;

        try {
            disabled = req.getParameter("disable")!=null;
            jdk = req.getParameter("jdk");
            setScm(SCMS.parseSCM(req));

            if(req.getParameter("hasSlaveAffinity")!=null) {
                assignedNode = Util.fixNull(req.getParameter("slave"));
                if(!assignedNode.equals("")) {
                    if(Hudson.getInstance().getSlave(assignedNode)==null) {
                        assignedNode = "";   // no such slave
                    }
                }
            } else {
                assignedNode = null;
            }
        } catch (FormException e) {
            throw new ServletException(e);
        }

        save();
        // SCM setting might have changed. Reparse POMs.
        rsp.sendRedirect("./startParsePOM");
    }

    /**
     * Serves the workspace files.
     */
    public void doWs( StaplerRequest req, StaplerResponse rsp ) throws IOException, ServletException, InterruptedException {
        FilePath ws = getWorkspace();
        if(!ws.exists()) {
            // if there's no workspace, report a nice error message
            rsp.forward(this,"noWorkspace",req);
        } else {
            new DirectoryBrowserSupport(this).serveFile(req, rsp, ws, "folder.gif", true);
        }
    }

    public TopLevelItemDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    public static final TopLevelItemDescriptor DESCRIPTOR = new TopLevelItemDescriptor(MavenModuleSet.class) {
        public String getDisplayName() {
            return "Building a maven2 project";
        }

        public MavenModuleSet newInstance(String name) {
            return new MavenModuleSet(name);
        }
    };

    static {
        Items.XSTREAM.alias("maven2-module-set", MavenModule.class);
    }
}
