/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.test.dunit.standalone;

import static org.apache.geode.distributed.ConfigurationProperties.DISABLE_AUTO_RECONNECT;
import static org.apache.geode.distributed.ConfigurationProperties.ENABLE_CLUSTER_CONFIGURATION;
import static org.apache.geode.distributed.ConfigurationProperties.JMX_MANAGER;
import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.LOG_LEVEL;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.apache.geode.distributed.ConfigurationProperties.USE_CLUSTER_CONFIGURATION;
import static org.apache.geode.distributed.ConfigurationProperties.VALIDATE_SERIALIZABLE_OBJECTS;
import static org.apache.geode.distributed.internal.DistributionConfig.GEMFIRE_PREFIX;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.rmi.AccessException;
import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Properties;

import batterytest.greplogs.ExpectedStrings;
import batterytest.greplogs.LogConsumer;
import hydra.MethExecutorResult;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.Assert;

import org.apache.geode.distributed.Locator;
import org.apache.geode.distributed.internal.DistributionConfig;
import org.apache.geode.distributed.internal.InternalLocator;
import org.apache.geode.distributed.internal.membership.gms.membership.GMSJoinLeave;
import org.apache.geode.internal.AvailablePortHelper;
import org.apache.geode.internal.Version;
import org.apache.geode.internal.logging.LogService;
import org.apache.geode.test.dunit.DUnitEnv;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.SerializableCallable;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.internal.JUnit4DistributedTestCase;

/**
 * A class to build a fake test configuration and launch some DUnit VMS.
 *
 * For use within eclipse. This class completely skips hydra and just starts some vms directly,
 * creating a fake test configuration
 *
 * Also, it's a good idea to set your working directory, because the test code a lot of files that
 * it leaves around.
 */
public class DUnitLauncher {

  /**
   * change this to use a different log level in unit tests
   */
  public static final String logLevel = System.getProperty("logLevel", "info");

  public static final String LOG4J = System.getProperty("log4j.configurationFile");

  /**
   * change this to have dunit/vmX directories deleted and recreated when processes are launched
   */
  public static final boolean MAKE_NEW_WORKING_DIRS =
      Boolean.getBoolean("makeNewWorkingDirsOnBounce");

  static int locatorPort;

  public static final int NUM_VMS = 4;
  private static final int DEBUGGING_VM_NUM = -1;
  private static final int LOCATOR_VM_NUM = -2;

  static final long STARTUP_TIMEOUT = 120 * 1000;
  private static final String STARTUP_TIMEOUT_MESSAGE =
      "VMs did not start up within " + (STARTUP_TIMEOUT / 1000) + " seconds";

  private static final String SUSPECT_FILENAME = "dunit_suspect.log";
  private static File DUNIT_SUSPECT_FILE;

  public static final String DUNIT_DIR = "dunit";
  public static final String WORKSPACE_DIR_PARAM = "WORKSPACE_DIR";
  public static final boolean LOCATOR_LOG_TO_DISK = Boolean.getBoolean("locatorLogToDisk");

  static final String MASTER_PARAM = "DUNIT_MASTER";

  public static final String RMI_PORT_PARAM = GEMFIRE_PREFIX + "DUnitLauncher.RMI_PORT";
  static final String VM_NUM_PARAM = GEMFIRE_PREFIX + "DUnitLauncher.VM_NUM";
  static final String VM_VERSION_PARAM = GEMFIRE_PREFIX + "DUnitLauncher.VM_VERSION";

  private static final String LAUNCHED_PROPERTY = GEMFIRE_PREFIX + "DUnitLauncher.LAUNCHED";

  private static Master master;

  private DUnitLauncher() {}

  private static boolean isHydra() {
    try {
      // TODO - this is hacky way to test for a hydra environment - see
      // if there is registered test configuration object.
      Class<?> clazz = Class.forName("hydra.TestConfig");
      Method getInstance = clazz.getMethod("getInstance", new Class[0]);
      getInstance.invoke(null);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Launch DUnit. If the unit test was launched through the hydra framework, leave the test alone.
   */
  public static void launchIfNeeded() {
    if (System.getProperties().contains(VM_NUM_PARAM)) {
      // we're a dunit child vm, do nothing.
      return;
    }

    if (!isHydra() && !isLaunched()) {
      try {
        launch();
      } catch (Exception e) {
        throw new RuntimeException("Unable to launch dunit VMs", e);
      }
    }

    Host.setAllVMsToCurrentVersion();
  }

  /**
   * Test it see if the eclise dunit environment is launched.
   */
  public static boolean isLaunched() {
    return Boolean.getBoolean(LAUNCHED_PROPERTY);
  }

  public static String getLocatorString() {
    return "localhost[" + locatorPort + "]";
  }

  private static void launch() throws URISyntaxException, AlreadyBoundException, IOException,
      InterruptedException, NotBoundException {
    DUNIT_SUSPECT_FILE = new File(SUSPECT_FILENAME);
    DUNIT_SUSPECT_FILE.delete();
    DUNIT_SUSPECT_FILE.deleteOnExit();

    // create an RMI registry and add an object to share our tests config
    int namingPort = AvailablePortHelper.getRandomAvailableTCPPort();
    Registry registry = LocateRegistry.createRegistry(namingPort);
    System.setProperty(RMI_PORT_PARAM, "" + namingPort);

    JUnit4DistributedTestCase.initializeBlackboard();

    final ProcessManager processManager = new ProcessManager(namingPort, registry);
    master = new Master(registry, processManager);
    registry.bind(MASTER_PARAM, master);

    // inhibit banners to make logs smaller
    System.setProperty(InternalLocator.INHIBIT_DM_BANNER, "true");

    // restrict membership ports to be outside of AvailablePort's range
    System.setProperty(DistributionConfig.RESTRICT_MEMBERSHIP_PORT_RANGE, "true");

    Runtime.getRuntime().addShutdownHook(new Thread() {
      public void run() {
        // System.out.println("shutting down DUnit JVMs");
        // for (int i=0; i<NUM_VMS; i++) {
        // try {
        // processManager.getStub(i).shutDownVM();
        // } catch (Exception e) {
        // System.out.println("exception shutting down vm_"+i+": " + e);
        // }
        // }
        // // TODO - hasLiveVMs always returns true
        // System.out.print("waiting for JVMs to exit");
        // long giveUp = System.currentTimeMillis() + 5000;
        // while (giveUp > System.currentTimeMillis()) {
        // if (!processManager.hasLiveVMs()) {
        // return;
        // }
        // System.out.print(".");
        // System.out.flush();
        // try {
        // Thread.sleep(1000);
        // } catch (InterruptedException e) {
        // break;
        // }
        // }
        // System.out.println("\nkilling any remaining JVMs");
        processManager.killVMs();
      }
    });

    // Create a VM for the locator
    processManager.launchVM(LOCATOR_VM_NUM);

    // wait for the VM to start up
    if (!processManager.waitForVMs(STARTUP_TIMEOUT)) {
      throw new RuntimeException(STARTUP_TIMEOUT_MESSAGE);
    }

    locatorPort = startLocator(registry);

    init(master);

    // Launch an initial set of VMs
    for (int i = 0; i < NUM_VMS; i++) {
      processManager.launchVM(i);
    }

    // wait for the VMS to start up
    if (!processManager.waitForVMs(STARTUP_TIMEOUT)) {
      throw new RuntimeException(STARTUP_TIMEOUT_MESSAGE);
    }

    // populate the Host class with our stubs. The tests use this host class
    DUnitHost host =
        new DUnitHost(InetAddress.getLocalHost().getCanonicalHostName(), processManager);
    host.init(registry, NUM_VMS);

  }

  public static Properties getDistributedSystemProperties() {
    Properties p = new Properties();
    p.setProperty(LOCATORS, getLocatorString());
    p.setProperty(MCAST_PORT, "0");
    p.setProperty(ENABLE_CLUSTER_CONFIGURATION, "false");
    p.setProperty(USE_CLUSTER_CONFIGURATION, "false");
    if (Version.CURRENT.ordinal() >= 75) {
      p.setProperty(VALIDATE_SERIALIZABLE_OBJECTS, "true");
    }
    p.setProperty(LOG_LEVEL, logLevel);
    return p;
  }

  /**
   * Add an appender to Log4j which sends all INFO+ messages to a separate file which will be used
   * later to scan for suspect strings. The pattern of the messages conforms to the original log
   * format so that hydra will be able to parse them.
   */
  private static void addSuspectFileAppender(final String workspaceDir) {
    final String suspectFilename = new File(workspaceDir, SUSPECT_FILENAME).getAbsolutePath();

    final LoggerContext appenderContext =
        ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(LogService.BASE_LOGGER_NAME))
            .getContext();

    final PatternLayout layout = PatternLayout.createLayout(
        "[%level{lowerCase=true} %date{yyyy/MM/dd HH:mm:ss.SSS z} <%thread> tid=%tid] %message%n%throwable%n",
        null, null, null, Charset.defaultCharset(), true, false, "", "");

    final FileAppender fileAppender = FileAppender.createAppender(suspectFilename, "true", "false",
        DUnitLauncher.class.getName(), "true", "false", "false", "0", layout, null, null, null,
        appenderContext.getConfiguration());
    fileAppender.start();

    LoggerConfig loggerConfig =
        appenderContext.getConfiguration().getLoggerConfig(LogService.BASE_LOGGER_NAME);
    loggerConfig.addAppender(fileAppender, Level.INFO, null);
  }

  private static int startLocator(Registry registry) throws IOException, NotBoundException {
    RemoteDUnitVMIF remote = (RemoteDUnitVMIF) registry.lookup("vm" + LOCATOR_VM_NUM);
    final File locatorLogFile =
        LOCATOR_LOG_TO_DISK ? new File("locator-" + locatorPort + ".log") : new File("");
    MethExecutorResult result = remote.executeMethodOnObject(new SerializableCallable() {
      public Object call() throws IOException {
        Properties p = getDistributedSystemProperties();
        // I never want this locator to end up starting a jmx manager
        // since it is part of the unit test framework
        p.setProperty(JMX_MANAGER, "false");
        // Disable the shared configuration on this locator.
        // Shared configuration tests create their own locator
        p.setProperty(ENABLE_CLUSTER_CONFIGURATION, "false");
        // Tell the locator it's the first in the system for
        // faster boot-up
        System.setProperty(GMSJoinLeave.BYPASS_DISCOVERY_PROPERTY, "true");
        // disable auto-reconnect - tests fly by so fast that it will never be
        // able to do so successfully anyway
        p.setProperty(DISABLE_AUTO_RECONNECT, "true");

        try {
          Locator.startLocatorAndDS(0, locatorLogFile, p);
          InternalLocator internalLocator = (InternalLocator) Locator.getLocator();
          locatorPort = internalLocator.getPort();
          internalLocator.resetInternalLocatorFileNamesWithCorrectPortNumber(locatorPort);
        } finally {
          System.getProperties().remove(GMSJoinLeave.BYPASS_DISCOVERY_PROPERTY);
        }

        return locatorPort;
      }
    }, "call");
    if (result.getException() != null) {
      RuntimeException ex = new RuntimeException("Failed to start locator", result.getException());
      ex.printStackTrace();
      throw ex;
    }
    return (Integer) result.getResult();
  }

  public static void init(MasterRemote master) {
    DUnitEnv.set(new StandAloneDUnitEnv(master));
    // fake out tests that are using a bunch of hydra stuff
    String workspaceDir = System.getProperty(DUnitLauncher.WORKSPACE_DIR_PARAM);
    workspaceDir = workspaceDir == null ? new File(".").getAbsolutePath() : workspaceDir;

    addSuspectFileAppender(workspaceDir);

    // Free off heap memory when disconnecting from the distributed system
    System.setProperty(GEMFIRE_PREFIX + "free-off-heap-memory", "true");

    // indicate that this CM is controlled by the eclipse dunit.
    System.setProperty(LAUNCHED_PROPERTY, "true");
  }

  public static void closeAndCheckForSuspects() {
    if (isLaunched()) {
      final boolean skipLogMsgs = ExpectedStrings.skipLogMsgs("dunit");
      final List<?> expectedStrings = ExpectedStrings.create("dunit");
      final LogConsumer logConsumer = new LogConsumer(skipLogMsgs, expectedStrings, "log4j", 5);

      final StringBuilder suspectStringBuilder = new StringBuilder();

      BufferedReader buffReader = null;
      FileChannel fileChannel = null;
      try {
        fileChannel = new FileOutputStream(DUNIT_SUSPECT_FILE, true).getChannel();
        buffReader = new BufferedReader(new FileReader(DUNIT_SUSPECT_FILE));
      } catch (FileNotFoundException e) {
        System.err.println("Could not find the suspect string output file: " + e);
        return;
      }
      try {
        String line;
        try {
          while ((line = buffReader.readLine()) != null) {
            final StringBuilder builder = logConsumer.consume(line);
            if (builder != null) {
              suspectStringBuilder.append(builder);
            }
          }
        } catch (IOException e) {
          System.err.println("Could not read the suspect string output file: " + e);
        }

        try {
          fileChannel.truncate(0);
        } catch (IOException e) {
          System.err.println("Could not truncate the suspect string output file: " + e);
        }

      } finally {
        try {
          buffReader.close();
          fileChannel.close();
        } catch (IOException e) {
          System.err.println("Could not close the suspect string output file: " + e);
        }
      }

      if (suspectStringBuilder.length() != 0) {
        System.err.println("Suspicious strings were written to the log during this run.\n"
            + "Fix the strings or use IgnoredException.addIgnoredException to ignore.\n"
            + suspectStringBuilder);

        Assert.fail("Suspicious strings were written to the log during this run.\n"
            + "Fix the strings or use IgnoredException.addIgnoredException to ignore.\n"
            + suspectStringBuilder);
      }
    }
  }


  public interface MasterRemote extends Remote {
    public int getLocatorPort() throws RemoteException;

    public void signalVMReady() throws RemoteException;

    public void ping() throws RemoteException;

    public BounceResult bounce(int pid) throws RemoteException;

    public BounceResult bounce(String version, int pid) throws RemoteException;
  }

  public static class Master extends UnicastRemoteObject implements MasterRemote {
    private static final long serialVersionUID = 1178600200232603119L;

    private final Registry registry;
    private final ProcessManager processManager;


    public Master(Registry registry, ProcessManager processManager) throws RemoteException {
      this.processManager = processManager;
      this.registry = registry;
    }

    public int getLocatorPort() throws RemoteException {
      return locatorPort;
    }

    public synchronized void signalVMReady() {
      processManager.signalVMReady();
    }

    public void ping() {
      // do nothing
    }

    @Override
    public BounceResult bounce(int pid) {
      return bounce(VersionManager.CURRENT_VERSION, pid);
    }

    @Override
    public BounceResult bounce(String version, int pid) {
      processManager.bounce(version, pid);

      try {
        if (!processManager.waitForVMs(STARTUP_TIMEOUT)) {
          throw new RuntimeException(STARTUP_TIMEOUT_MESSAGE);
        }
        RemoteDUnitVMIF remote =
            (RemoteDUnitVMIF) registry.lookup(VM.getVMName(VersionManager.CURRENT_VERSION, pid));
        return new BounceResult(pid, remote);
      } catch (RemoteException | NotBoundException e) {
        throw new RuntimeException("could not lookup name", e);
      } catch (InterruptedException e) {
        throw new RuntimeException("Failed waiting for VM", e);
      }
    }
  }

  private static class DUnitHost extends Host {
    private static final long serialVersionUID = -8034165624503666383L;

    private transient final VM debuggingVM;

    private transient ProcessManager processManager;

    public DUnitHost(String hostName, ProcessManager processManager) throws RemoteException {
      super(hostName);
      this.debuggingVM = new VM(this, -1, new RemoteDUnitVM());
      this.processManager = processManager;
    }

    public void init(Registry registry, int numVMs)
        throws AccessException, RemoteException, NotBoundException, InterruptedException {
      for (int i = 0; i < numVMs; i++) {
        RemoteDUnitVMIF remote = processManager.getStub(i);
        addVM(i, remote);
      }

      addLocator(LOCATOR_VM_NUM, processManager.getStub(LOCATOR_VM_NUM));

      addHost(this);
    }

    @Override
    public VM getVM(int n) {
      return getVM(VersionManager.CURRENT_VERSION, n);
    }

    @Override
    public VM getVM(String version, int n) {
      if (n == DEBUGGING_VM_NUM) {
        // for ease of debugging, pass -1 to get the local VM
        return debuggingVM;
      }

      if (n < getVMCount()) {
        VM current = super.getVM(n);
        if (!current.getVersion().equals(version)) {
          System.out.println(
              "Bouncing VM" + n + " from version " + current.getVersion() + " to " + version);
          current.bounce(version);
        }
        return current;
      }

      int oldVMCount = getVMCount();
      if (n >= oldVMCount) {
        // If we don't have a VM with that number, dynamically create it.
        try {
          // first fill in any gaps, to keep the superclass, Host, happy
          for (int i = oldVMCount; i < n; i++) {
            processManager.launchVM(i);
          }
          processManager.waitForVMs(STARTUP_TIMEOUT);

          for (int i = oldVMCount; i < n; i++) {
            addVM(i, processManager.getStub(i));
          }

          // now create the one we really want
          processManager.launchVM(version, n, false);
          processManager.waitForVMs(STARTUP_TIMEOUT);
          addVM(n, processManager.getStub(n));

        } catch (IOException | InterruptedException | NotBoundException e) {
          throw new RuntimeException("Could not dynamically launch vm + " + n, e);
        }
      }

      return super.getVM(n);
    }
  }
}
