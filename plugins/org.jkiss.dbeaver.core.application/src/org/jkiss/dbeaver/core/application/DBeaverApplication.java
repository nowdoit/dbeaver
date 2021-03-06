/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2017 Serge Rider (serge@jkiss.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.core.application;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferenceConstants;
import org.eclipse.ui.PlatformUI;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBeaverPreferences;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.core.DBeaverCore;
import org.jkiss.dbeaver.core.DBeaverUI;
import org.jkiss.dbeaver.core.application.rpc.DBeaverInstanceServer;
import org.jkiss.dbeaver.core.application.rpc.IInstanceController;
import org.jkiss.dbeaver.core.application.rpc.InstanceClient;
import org.jkiss.dbeaver.model.app.DBASecureStorage;
import org.jkiss.dbeaver.model.app.DBPApplication;
import org.jkiss.dbeaver.model.impl.app.DefaultSecureStorage;
import org.jkiss.dbeaver.model.runtime.*;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.dbeaver.utils.SystemVariablesResolver;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.IOUtils;
import org.jkiss.utils.StandardConstants;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;

import java.io.*;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.*;
import java.util.List;

/**
 * This class controls all aspects of the application's execution
 */
public class DBeaverApplication implements IApplication, DBPApplication {

    private static final Log log = Log.getLog(DBeaverApplication.class);

    public static final String APPLICATION_PLUGIN_ID = "org.jkiss.dbeaver.core.application";

    public static final String WORKSPACE_DIR_LEGACY = ".dbeaver"; //$NON-NLS-1$
    public static final String WORKSPACE_DIR_4 = ".dbeaver4"; //$NON-NLS-1$

    public static final String WORKSPACE_DIR_CURRENT = WORKSPACE_DIR_4;
    public static final String WORKSPACE_DIR_PREVIOUS[] = { WORKSPACE_DIR_LEGACY };

    public static final String WORKSPACE_PROPS_FILE = "dbeaver-workspace.properties"; //$NON-NLS-1$

    static final String VERSION_PROP_PRODUCT_NAME = "product-name";
    static final String VERSION_PROP_PRODUCT_VERSION = "product-version";

    private static DBeaverApplication instance;
    private IInstanceController instanceServer;

    private OutputStream debugWriter;
    private PrintStream oldSystemOut;
    private PrintStream oldSystemErr;

    private Display display = null;

    static {
        // Explicitly set UTF-8 as default file encoding
        // In some places Eclipse reads this property directly.
        //System.setProperty(StandardConstants.ENV_FILE_ENCODING, GeneralUtils.UTF8_ENCODING);
    }

    /**
     * Gets singleton instance of DBeaver application
     * @return application or null if application wasn't started or was stopped.
     */
    public static DBeaverApplication getInstance() {
        return instance;
    }

    @Override
    public Object start(IApplicationContext context) {
        instance = this;

        Location instanceLoc = Platform.getInstanceLocation();
        if (!instanceLoc.isSet()) {
            if (!setDefaultWorkspacePath(instanceLoc)) {
                return IApplication.EXIT_OK;
            }
        }

        // Add bundle load logger
        Bundle brandingBundle = context.getBrandingBundle();
        if (brandingBundle != null) {
            BundleContext bundleContext = brandingBundle.getBundleContext();
            if (bundleContext != null) {
                bundleContext.addBundleListener(new BundleLoadListener());
            }
        }
        Log.addListener(new Log.Listener() {
            @Override
            public void loggedMessage(Object message, Throwable t) {
                DBeaverSplashHandler.showMessage(CommonUtils.toString(message));
            }
        });

        final Runtime runtime = Runtime.getRuntime();

        // Init Core plugin and mark it as standalone version
        DBeaverCore.setApplication(this);

        initDebugWriter();

        log.debug(GeneralUtils.getProductTitle() + " is starting"); //$NON-NLS-1$
        log.debug("Install path: '" + SystemVariablesResolver.getInstallPath() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        log.debug("Instance path: '" + instanceLoc.getURL() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        log.debug("Memory available " + (runtime.totalMemory() / (1024 * 1024)) + "Mb/" + (runtime.maxMemory() / (1024 * 1024)) + "Mb");

        // Write version info
        writeWorkspaceInfo();

        // Run instance server
        instanceServer = DBeaverInstanceServer.startInstanceServer();

        // Set default resource encoding to UTF-8
        String defEncoding = DBeaverCore.getGlobalPreferenceStore().getString(DBeaverPreferences.DEFAULT_RESOURCE_ENCODING);
        if (CommonUtils.isEmpty(defEncoding)) {
            defEncoding = GeneralUtils.UTF8_ENCODING;
        }
        ResourcesPlugin.getPlugin().getPluginPreferences().setValue(ResourcesPlugin.PREF_ENCODING, defEncoding);

        // Create display
        getDisplay();

        // Prefs default
        PlatformUI.getPreferenceStore().setDefault(
            IWorkbenchPreferenceConstants.KEY_CONFIGURATION_ID,
            ApplicationWorkbenchAdvisor.DBEAVER_SCHEME_NAME);
        try {
            log.debug("Run workbench");
            int returnCode = PlatformUI.createAndRunWorkbench(display, createWorkbenchAdvisor());
            if (returnCode == PlatformUI.RETURN_RESTART) {
                return IApplication.EXIT_RESTART;
            }
            return IApplication.EXIT_OK;
        } finally {
/*
            try {
                Job.getJobManager().join(null, new NullProgressMonitor());
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
*/
            display.dispose();
            display = null;
        }
    }

    private Display getDisplay() {
        if (display == null) {
            log.debug("Create display");
            display = PlatformUI.createDisplay();
        }
        return display;
    }

    private boolean setDefaultWorkspacePath(Location instanceLoc) {
        String defaultHomePath = getDefaultWorkspaceLocation(WORKSPACE_DIR_CURRENT).getAbsolutePath();
        final File homeDir = new File(defaultHomePath);
        if (!homeDir.exists()) {
            File previousVersionWorkspaceDir = null;
            for (String oldDir : WORKSPACE_DIR_PREVIOUS) {
                final File oldWorkspaceDir = new File(getDefaultWorkspaceLocation(oldDir).getAbsolutePath());
                if (oldWorkspaceDir.exists() && GeneralUtils.getMetadataFolder(oldWorkspaceDir).exists()) {
                    previousVersionWorkspaceDir = oldWorkspaceDir;
                    break;
                }
            }
            if (previousVersionWorkspaceDir != null) {
                DBeaverSettingsImporter importer = new DBeaverSettingsImporter(this, getDisplay());
                importer.migrateFromPreviousVersion(previousVersionWorkspaceDir, homeDir);
            }
        }
        try {
            // Make URL manually because file.toURI().toURL() produces bad path (with %20).
            final URL defaultHomeURL = new URL(
                "file",  //$NON-NLS-1$
                null,
                defaultHomePath);
            boolean keepTrying = true;
            while (keepTrying) {
                if (!instanceLoc.set(defaultHomeURL, true)) {
                    if (handleCommandLine(defaultHomePath)) {
                        return false;
                    }
                    // Can't lock specified path
                    int msgResult = showMessageBox(
                        "DBeaver - Can't lock workspace",
                        "Can't lock workspace at " + defaultHomePath + ".\n" +
                            "It seems that you have another DBeaver instance running.\n" +
                            "You may ignore it and work without lock but it is recommended to shutdown previous instance otherwise you may corrupt workspace data.",
                        SWT.ICON_WARNING | SWT.IGNORE | SWT.RETRY | SWT.ABORT);

                    switch (msgResult) {
                        case SWT.ABORT:
                            return true;
                        case SWT.IGNORE:
                            instanceLoc.set(defaultHomeURL, false);
                            keepTrying = false;
                            break;
                        case SWT.RETRY:
                            break;
                    }
                } else {
                    break;
                }
            }

        } catch (Throwable e) {
            // Just skip it
            // Error may occur if -data parameter was specified at startup
            System.err.println("Can't switch workspace to '" + defaultHomePath + "' - " + e.getMessage());  //$NON-NLS-1$ //$NON-NLS-2$
        }
        return true;
    }

    private void writeWorkspaceInfo() {
        final File metadataFolder = GeneralUtils.getMetadataFolder();
        writeWorkspaceInfo(metadataFolder);
    }

    private void writeWorkspaceInfo(File metadataFolder) {
        File versionFile = new File(metadataFolder, WORKSPACE_PROPS_FILE);

        Properties props = new Properties();
        props.setProperty(VERSION_PROP_PRODUCT_NAME, GeneralUtils.getProductName());
        props.setProperty(VERSION_PROP_PRODUCT_VERSION, GeneralUtils.getProductVersion().toString());

        try (OutputStream os = new FileOutputStream(versionFile)) {
            props.store(os, "DBeaver workspace version");
        } catch (Exception e) {
            log.error(e);
        }
    }

    Properties readWorkspaceInfo(File metadataFolder) {
        Properties props = new Properties();

        File versionFile = new File(metadataFolder, WORKSPACE_PROPS_FILE);
        if (versionFile.exists()) {
            try (InputStream is = new FileInputStream(versionFile)) {
                props.load(is);
            } catch (Exception e) {
                log.error(e);
            }
        }
        return props;
    }

    @NotNull
    protected ApplicationWorkbenchAdvisor createWorkbenchAdvisor() {
        return new ApplicationWorkbenchAdvisor();
    }

    private boolean handleCommandLine(String instanceLoc) {
        CommandLine commandLine = getCommandLine();
        if (commandLine == null) {
            return false;
        }
        if (commandLine.hasOption(DBeaverCommandLine.PARAM_HELP)) {
            HelpFormatter helpFormatter = new HelpFormatter();
            helpFormatter.setWidth(120);
            helpFormatter.setOptionComparator(new Comparator<Option>() {
                @Override
                public int compare(Option o1, Option o2) {
                    return 0;
                }
            });
            helpFormatter.printHelp("dbeaver", GeneralUtils.getProductTitle(), DBeaverCommandLine.ALL_OPTIONS, "(C) 2016 JKISS", true);
            return true;
        }

        try {
            IInstanceController controller = InstanceClient.createClient(instanceLoc);
            if (controller == null) {
                return false;
            }

            return executeCommandLineCommands(commandLine, controller);
        } catch (RemoteException e) {
            log.error("Error calling remote server", e);
            return true;
        } catch (Throwable e) {
            log.error("Internal error while calling remote server", e);
            return false;
        }
    }

    @Override
    public void stop() {
        log.debug("DBeaver is stopping"); //$NON-NLS-1$
        try {
            final IWorkbench workbench = PlatformUI.getWorkbench();
            if (workbench == null)
                return;

            instanceServer = null;
            DBeaverInstanceServer.stopInstanceServer();

            final Display display = workbench.getDisplay();
            DBeaverUI.syncExec(new Runnable() {
                @Override
                public void run() {
                    if (!display.isDisposed())
                        workbench.close();
                }
            });

        } catch (Throwable e) {
            log.error(e);
        } finally {
            instance = null;
            stopDebugWriter();
        }
    }

    private void initDebugWriter() {
        File logPath = GeneralUtils.getMetadataFolder();
        File debugLogFile = new File(logPath, "dbeaver-debug.log"); //$NON-NLS-1$
        if (debugLogFile.exists()) {
            if (!debugLogFile.delete()) {
                System.err.println("Can't delete debug log file"); //$NON-NLS-1$
            }
        }
        try {
            debugWriter = new FileOutputStream(debugLogFile);
            oldSystemOut = System.out;
            oldSystemErr = System.err;
            System.setOut(new PrintStream(new ProxyPrintStream(debugWriter, oldSystemOut)));
            System.setErr(new PrintStream(new ProxyPrintStream(debugWriter, oldSystemErr)));
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
    }

    private void stopDebugWriter() {
        if (oldSystemOut != null) System.setOut(oldSystemOut);
        if (oldSystemErr != null) System.setErr(oldSystemErr);

        if (debugWriter != null) {
            IOUtils.close(debugWriter);
            debugWriter = null;
        }
    }

    public static boolean executeCommandLineCommands(CommandLine commandLine, IInstanceController controller) throws Exception {
        if (commandLine == null) {
            return false;
        }
        String[] files = commandLine.getOptionValues(DBeaverCommandLine.PARAM_FILE);
        String[] fileArgs = commandLine.getArgs();
        if (!ArrayUtils.isEmpty(files) || !ArrayUtils.isEmpty(fileArgs)) {
            List<String> fileNames = new ArrayList<>();
            if (!ArrayUtils.isEmpty(files)) {
                Collections.addAll(fileNames, files);
            }
            if (!ArrayUtils.isEmpty(fileArgs)) {
                Collections.addAll(fileNames, fileArgs);
            }
            controller.openExternalFiles(fileNames.toArray(new String[fileNames.size()]));
            return true;
        }
        if (commandLine.hasOption(DBeaverCommandLine.PARAM_STOP)) {
            controller.quit();
            return true;
        }
        if (commandLine.hasOption(DBeaverCommandLine.PARAM_THREAD_DUMP)) {
            String threadDump = controller.getThreadDump();
            System.out.println(threadDump);
            return true;
        }
        return false;
    }

    public IInstanceController getInstanceServer() {
        return instanceServer;
    }

    private static File getDefaultWorkspaceLocation(String path) {
        return new File(
            System.getProperty(StandardConstants.ENV_USER_HOME),
            path);
    }

    public static CommandLine getCommandLine() {
        try {
            return new DefaultParser().parse(DBeaverCommandLine.ALL_OPTIONS, Platform.getApplicationArgs(), false);
        } catch (Exception e) {
            log.error("Error parsing command line: " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean isStandalone() {
        return true;
    }

    @NotNull
    @Override
    public DBASecureStorage getSecureStorage() {
        return DefaultSecureStorage.INSTANCE;
    }

    int showMessageBox(String title, String message, int style) {
        // Can't lock specified path
        Shell shell = new Shell(getDisplay(), SWT.ON_TOP);
        shell.setText(GeneralUtils.getProductTitle());
        MessageBox messageBox = new MessageBox(shell, style);
        messageBox.setText(title);
        messageBox.setMessage(message);
        int msgResult = messageBox.open();
        shell.dispose();
        return msgResult;
    }

    private static class BundleLoadListener implements BundleListener {
        @Override
        public void bundleChanged(BundleEvent event) {
            String message = null;

            if (event.getType() == BundleEvent.STARTED) {
                message = "> Start " + event.getBundle().getSymbolicName() + " [" + event.getBundle().getVersion() + "]";
            } else if (event.getType() == BundleEvent.STOPPED) {
                message = "< Stop " + event.getBundle().getSymbolicName() + " [" + event.getBundle().getVersion() + "]";
            }
            if (message != null) {
                log.debug(message);
            }
        }
    }

    private class ProxyPrintStream extends OutputStream {
        private final OutputStream debugWriter;
        private final OutputStream stdOut;

        public ProxyPrintStream(OutputStream debugWriter, OutputStream stdOut) {
            this.debugWriter = debugWriter;
            this.stdOut = stdOut;
        }

        @Override
        public void write(int b) throws IOException {
            debugWriter.write(b);
            stdOut.write(b);
        }
    }

}
