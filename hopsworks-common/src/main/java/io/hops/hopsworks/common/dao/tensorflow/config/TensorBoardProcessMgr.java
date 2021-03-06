/*
 * This file is part of Hopsworks
 * Copyright (C) 2018, Logical Clocks AB. All rights reserved
 *
 * Hopsworks is free software: you can redistribute it and/or modify it under the terms of
 * the GNU Affero General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * Hopsworks is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package io.hops.hopsworks.common.dao.tensorflow.config;

import io.hops.hopsworks.common.dao.hdfsUser.HdfsUsers;
import io.hops.hopsworks.common.dao.hdfsUser.HdfsUsersFacade;
import io.hops.hopsworks.common.dao.project.Project;
import io.hops.hopsworks.common.dao.tensorflow.TensorBoard;
import io.hops.hopsworks.common.dao.user.Users;
import io.hops.hopsworks.common.hdfs.DistributedFileSystemOps;
import io.hops.hopsworks.common.hdfs.DistributedFsService;
import io.hops.hopsworks.common.security.CertificateMaterializer;
import io.hops.hopsworks.common.util.HopsUtils;
import io.hops.hopsworks.common.util.OSProcessExecutor;
import io.hops.hopsworks.common.util.ProcessDescriptor;
import io.hops.hopsworks.common.util.ProcessResult;
import io.hops.hopsworks.common.util.Settings;
import io.hops.hopsworks.exceptions.TensorBoardException;
import io.hops.hopsworks.restutils.RESTCodes;
import org.apache.commons.io.FileUtils;

import javax.ejb.ConcurrencyManagement;
import javax.ejb.ConcurrencyManagementType;
import javax.ejb.DependsOn;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * *
 * This class wraps a bash script with sudo rights that can be executed by the node['hopsworks']['user'].
 * /srv/hops/domains/domain1/bin/tensorboard.sh
 * The bash script has several commands with parameters that can be executed.
 * This class provides a Java interface for executing the commands.
 */
@Stateless
@ConcurrencyManagement(ConcurrencyManagementType.CONTAINER)
@DependsOn("Settings")
public class TensorBoardProcessMgr {

  private static final Logger LOGGER = Logger.getLogger(TensorBoardProcessMgr.class.getName());

  @EJB
  private Settings settings;
  @EJB
  private HdfsUsersFacade hdfsUsersFacade;
  @EJB
  private DistributedFsService dfsService;
  @EJB
  private CertificateMaterializer certificateMaterializer;
  @EJB
  private OSProcessExecutor osProcessExecutor;

  /**
   * Start the TensorBoard process
   * @param project
   * @param user
   * @param hdfsUser
   * @param hdfsLogdir
   * @return
   * @throws IOException
   */
  @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
  public TensorBoardDTO startTensorBoard(Project project, Users user, HdfsUsers hdfsUser, String hdfsLogdir,
                                         String tfLdLibraryPath, String tensorBoardDirectory)
          throws IOException, TensorBoardException {

    String prog = settings.getHopsworksDomainDir() + "/bin/tensorboard.sh";
    Integer port = 0;
    BigInteger pid = null;
    String tbBasePath = settings.getStagingDir() + Settings.TENSORBOARD_DIRS;
    String tbSecretDir = tbBasePath + tensorBoardDirectory;
    String certsPath = "";

    File tbDir = new File(tbSecretDir);
    if(tbDir.exists()) {
      for(File file: tbDir.listFiles()) {
        if(file.getName().endsWith(".pid")) {
          String pidContents = com.google.common.io.Files.readFirstLine(file, Charset.defaultCharset());
          try {
            pid = BigInteger.valueOf(Long.parseLong(pidContents));
            if (pid != null && ping(pid) == 0) {
              killTensorBoard(pid);
            }
          } catch(NumberFormatException nfe) {
            LOGGER.log(Level.WARNING, "Expected number in pidfile " +
                    file.getAbsolutePath() + " got " + pidContents);
          }
        }
      }
      FileUtils.deleteDirectory(tbDir);
    }
    tbDir.mkdirs();

    DistributedFileSystemOps dfso = dfsService.getDfsOps();
    try {
      certsPath = tbSecretDir + "/certs";
      File certsDir = new File(certsPath);
      certsDir.mkdirs();
      HopsUtils.materializeCertificatesForUserCustomDir(project.getName(), user.getUsername(), settings
          .getHdfsTmpCertDir(), dfso, certificateMaterializer, settings, certsPath);
    } catch (IOException ioe) {
      LOGGER.log(Level.SEVERE, "Failed in materializing certificates for " +
          hdfsUser + " in directory " + certsPath, ioe);
      HopsUtils.cleanupCertificatesForUserCustomDir(user.getUsername(), project.getName(),
          settings.getHdfsTmpCertDir(), certificateMaterializer, certsPath, settings);
      throw new TensorBoardException(RESTCodes.TensorBoardErrorCode.TENSORBOARD_START_ERROR, Level.SEVERE,
              "Failed to start TensorBoard", "An exception occurred while materializing certificates", ioe);
    } finally {
      if (dfso != null) {
        dfsService.closeDfsClient(dfso);
      }
    }

    String anacondaEnvironmentPath = settings.getAnacondaProjectDir(project);
    int retries = 3;
    while(retries > 0) {
      try {
        if(retries == 0) {
          throw new IOException("Failed to start TensorBoard for project=" + project.getName() + ", user="
                  + user.getUid());
        }

        // use pidfile to kill any running servers
        port = ThreadLocalRandom.current().nextInt(40000, 59999);
  
        ProcessDescriptor processDescriptor = new ProcessDescriptor.Builder()
          .addCommand("/usr/bin/sudo")
          .addCommand(prog)
          .addCommand("start")
          .addCommand(hdfsUser.getName())
          .addCommand(hdfsLogdir)
          .addCommand(tbSecretDir)
          .addCommand(port.toString())
          .addCommand(anacondaEnvironmentPath)
          .addCommand(settings.getHadoopVersion())
          .addCommand(settings.getJavaHome())
          .addCommand(tfLdLibraryPath)
          .ignoreOutErrStreams(true)
          .build();
        LOGGER.log(Level.FINE, processDescriptor.toString());

        ProcessResult processResult = osProcessExecutor.execute(processDescriptor);
        if (!processResult.processExited()) {
          throw new IOException("TensorBoard start process timed out!");
        }
        int exitValue = processResult.getExitCode();
        String pidPath = tbSecretDir + File.separator + port + ".pid";
        File pidFile = new File(pidPath);
        // Read the pid for TensorBoard server
        if(pidFile.exists()) {
          String pidContents = com.google.common.io.Files.readFirstLine(pidFile, Charset.defaultCharset());
          pid = BigInteger.valueOf(Long.parseLong(pidContents));
        } else {
          throw new IOException("No .pid file found for TensorBoard" + pidPath);
        }
        if(exitValue == 0 && pid != null) {
          TensorBoardDTO tensorBoardDTO = new TensorBoardDTO();
          String host = null;
          try {
            host = InetAddress.getLocalHost().getHostAddress();
          } catch (UnknownHostException ex) {
            LOGGER.log(Level.SEVERE, null, ex);
          }
          tensorBoardDTO.setEndpoint(host + ":" + port);
          tensorBoardDTO.setPid(pid);
          return tensorBoardDTO;
        } else {
          LOGGER.log(Level.SEVERE,"Failed starting TensorBoard got exitcode " + exitValue + " retrying on new port");
          if(pid != null) {
            this.killTensorBoard(pid);
          }
          pid = null;
        }

      } catch (Exception ex) {

        LOGGER.log(Level.SEVERE, "Problem starting TensorBoard: {0}", ex);

        if(pid != null && this.ping(pid) == 0) {
          this.killTensorBoard(pid);
        }

      } finally {
        retries--;
      }
    }

    dfso = dfsService.getDfsOps();
    certsPath = tbBasePath + "/certs";
    try {
      HopsUtils.cleanupCertificatesForUserCustomDir(user.getUsername(), project.getName(),
              settings.getHdfsTmpCertDir(), certificateMaterializer, certsPath, settings);
    } finally {
      if (dfso != null) {
        dfsService.closeDfsClient(dfso);
      }
    }

    this.removeTensorBoardDirectory(tbSecretDir);

    throw new TensorBoardException(RESTCodes.TensorBoardErrorCode.TENSORBOARD_START_ERROR, Level.SEVERE,
            "Failed to start TensorBoard after exhausting retry attempts");
  }

  /**
   * Kill the TensorBoard process
   * @param pid
   * @return
   */
  @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
  public int killTensorBoard(BigInteger pid) {

    String prog = settings.getHopsworksDomainDir() + "/bin/tensorboard.sh";
    int exitValue;

    ProcessDescriptor processDescriptor = new ProcessDescriptor.Builder()
        .addCommand("/usr/bin/sudo")
        .addCommand(prog)
        .addCommand("kill")
        .addCommand(pid.toString())
        .ignoreOutErrStreams(true)
        .build();
    LOGGER.log(Level.FINE, processDescriptor.toString());
    
    try {
      ProcessResult processResult = osProcessExecutor.execute(processDescriptor);
      if (!processResult.processExited()) {
        LOGGER.log(Level.SEVERE,"Failed to kill TensorBoard");
      }
      exitValue = processResult.getExitCode();
    } catch (IOException ex) {
      exitValue=2;
      LOGGER.log(Level.SEVERE,"Failed to kill TensorBoard" , ex);
    }
    return exitValue;
  }

  /**
   * Kill the TensorBoard process
   * @param tb
   * @return
   */
  @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
  public int killTensorBoard(TensorBoard tb) {

    String prog = settings.getHopsworksDomainDir() + "/bin/tensorboard.sh";
    int exitValue;

    ProcessDescriptor processDescriptor = new ProcessDescriptor.Builder()
        .addCommand("/usr/bin/sudo")
        .addCommand(prog)
        .addCommand("kill")
        .addCommand(tb.getPid().toString())
        .ignoreOutErrStreams(true)
        .build();
    LOGGER.log(Level.FINE, processDescriptor.toString());
    try {
      ProcessResult processResult = osProcessExecutor.execute(processDescriptor);
      if (!processResult.processExited()) {
        LOGGER.log(Level.SEVERE, "Failed to kill TensorBoard, process time-out");
      }
      exitValue = processResult.getExitCode();
    } catch (IOException ex) {
      exitValue=2;
      LOGGER.log(Level.SEVERE,"Failed to kill TensorBoard" , ex);
    }
    return exitValue;
  }

  /**
   * Dematerialize and remove TensorBoard directory
   * @param tb
   * @throws IOException
   */
  @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
  public void cleanup(TensorBoard tb) throws TensorBoardException {

    String tbBasePath = settings.getStagingDir() + Settings.TENSORBOARD_DIRS;
    String tbPath = tbBasePath + tb.getSecret();

    // Dematerialize certificates
    String certsPath = tbPath + "/certs";
    DistributedFileSystemOps dfso = dfsService.getDfsOps();
    try {
      HopsUtils.cleanupCertificatesForUserCustomDir(tb.getUsers().getUsername(), tb.getProject().getName(),
              settings.getHdfsTmpCertDir(), certificateMaterializer, certsPath, settings);
    } finally {
      if (dfso != null) {
        dfsService.closeDfsClient(dfso);
      }
    }

    removeTensorBoardDirectory(tbPath);
  }

  /**
   * Remove TensorBoard directory
   * @param tensorBoardDirectoryPath
   * @throws IOException
   */
  @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
  public void removeTensorBoardDirectory(String tensorBoardDirectoryPath) throws TensorBoardException {

    // Remove directory
    String prog = settings.getHopsworksDomainDir() + "/bin/tensorboard.sh";

    ProcessDescriptor processDescriptor = new ProcessDescriptor.Builder()
            .addCommand("/usr/bin/sudo")
            .addCommand(prog)
            .addCommand("cleanup")
            .addCommand(tensorBoardDirectoryPath)
            .ignoreOutErrStreams(true)
            .build();

    LOGGER.log(Level.FINE, processDescriptor.toString());
    try {
      ProcessResult processResult = osProcessExecutor.execute(processDescriptor);
      if (!processResult.processExited() || processResult.getExitCode() != 0) {
        throw new TensorBoardException(RESTCodes.TensorBoardErrorCode.TENSORBOARD_CLEANUP_ERROR, Level.SEVERE,
                "Failed to cleanup TensorBoard", "Could not delete TensorBoard directory: "
                + tensorBoardDirectoryPath
          );
      }
    } catch (IOException ex) {
      throw new TensorBoardException(RESTCodes.TensorBoardErrorCode.TENSORBOARD_CLEANUP_ERROR, Level.SEVERE,
              "Failed to cleanup TensorBoard", "Could not delete TensorBoard directory: "
              + tensorBoardDirectoryPath, ex);
    }
  }


  /**
   * Check to see if the process is running and is a TensorBoard started by tensorboard.sh
   * @param pid
   * @return
   */
  @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
  public int ping(BigInteger pid) {

    String prog = settings.getHopsworksDomainDir() + "/bin/tensorboard.sh";
    int exitValue = 1;

    ProcessDescriptor processDescriptor = new ProcessDescriptor.Builder()
        .addCommand("/usr/bin/sudo")
        .addCommand(prog)
        .addCommand("ping")
        .addCommand(pid.toString())
        .ignoreOutErrStreams(true)
        .build();
    LOGGER.log(Level.FINE, processDescriptor.toString());
    try {
      ProcessResult processResult = osProcessExecutor.execute(processDescriptor);
      if (!processResult.processExited()) {
        LOGGER.log(Level.SEVERE, "Pinging time-out");
        exitValue = 2;
      }
      exitValue = processResult.getExitCode();
    } catch (IOException ex) {
      LOGGER.log(Level.SEVERE, "Problem pinging: {0}", ex.toString());
    }
    return exitValue;
  }
}