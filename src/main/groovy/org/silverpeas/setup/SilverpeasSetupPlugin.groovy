/*
    Copyright (C) 2000 - 2019 Silverpeas

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    As a special exception to the terms and conditions of version 3.0 of
    the GPL, you may redistribute this Program in connection with Free/Libre
    Open Source Software ("FLOSS") applications as described in Silverpeas's
    FLOSS exception.  You should have recieved a copy of the text describing
    the FLOSS exception, and it is also available here:
    "http://www.silverpeas.org/docs/core/legal/floss_exception.html"

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.silverpeas.setup

import org.gradle.api.*
import org.gradle.api.provider.Property
import org.silverpeas.setup.api.*
import org.silverpeas.setup.configuration.JBossConfigurationTask
import org.silverpeas.setup.configuration.SilverpeasConfigurationTask
import org.silverpeas.setup.configuration.VariableReplacement
import org.silverpeas.setup.construction.SilverpeasBuilder
import org.silverpeas.setup.construction.SilverpeasConstructionTask
import org.silverpeas.setup.installation.SilverpeasInstallationTask
import org.silverpeas.setup.migration.SilverpeasMigrationTask
import org.silverpeas.setup.security.Encryption
import org.silverpeas.setup.security.EncryptionFactory

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.silverpeas.setup.api.SilverpeasSetupTaskNames.*

/**
 * This plugin aims to prepare the configuration and to setup Silverpeas.
 * For doing, it loads both the default and the customer configuration file of Silverpeas and it
 * registers two tasks, one dedicated to configure JBoss/Wildfly for Silverpeas and another to
 * configure Silverpeas.
 * <p>
 * The String class is dynamically extended with an additional method, String#asPath(),
 * that returns a Path instance from the path representation of the String. The difference between
 * this method and {@code Paths # getPath ( String )} is that the former takes into account any
 * system properties or environment variables in the path; if the path contains any system or
 * environment variables, they are then replaced by their value.
 * </p>
 */
class SilverpeasSetupPlugin implements Plugin<Project> {

  public static final String EXTENSION = 'silversetup'
  public static final String JBOSS_OUTPUT_LOG = 'jboss-output.log'

  @Override
  void apply(Project project) {
    def extension = project.extensions.create(EXTENSION, SilverpeasSetupExtension, project)
    initSilverpeasSetupExtention(extension)

    project.gradle.buildFinished {
      extension.config.context.save()
    }

    SilverpeasSetupService setupService = new SilverpeasSetupService(extension.settings)
    ManagedBeanContainer.registry()
        .register(new DataSourceProvider(extension.settings))
        .register(setupService)
    String.metaClass.asPath = { Paths.get(setupService.expanseVariables(delegate.toString())) }

    Property<JBossServer> jBossServer = project.objects.property(JBossServer)
    project.afterEvaluate { Project currentProject, ProjectState state ->
      SilverpeasSetupExtension silverSetup =
          (SilverpeasSetupExtension) currentProject.extensions.getByName(EXTENSION)
      silverSetup.settings.DEV_MODE = silverSetup.installation.developmentMode.get() as String
      if (silverSetup.logging.useLogger) {
        initLogging(currentProject, silverSetup.logging)
      }
      silverSetup.settings.SILVERPEAS_VERSION = currentProject.version as String
      jBossServer.set(new JBossServer(extension.jbossHome.path)
          .withStartingTimeout(extension.timeout.get())
          .redirectOutputTo(new File(extension.logging.logDir, JBOSS_OUTPUT_LOG)))
    }

    Task construction = project.tasks.create(CONSTRUCT.name, SilverpeasConstructionTask) {
      it.silverpeasHome    = extension.silverpeasHome
      it.installation      = extension.installation
      it.settings          = extension.settings
    }

    Task jbossConf = project.tasks.create(CONFIGURE_JBOSS.name, JBossConfigurationTask) {
      it.driversDir   = extension.installation.dsDriversDir.get()
      it.config       = extension.config
      it.jboss        = jBossServer
      it.settings     = extension.settings
    }

    Task silverpeasConf = project.tasks.create(CONFIGURE_SILVERPEAS.name, SilverpeasConfigurationTask) {
      it.silverpeasHome = extension.silverpeasHome
      it.config         = extension.config
      it.settings       = extension.settings
    }.dependsOn(construction)

    Task configuration = project.tasks.create(CONFIGURE.name) {
      it.description = 'Configures both JBoss and Silverpeas'
      it.group = 'Build'
    }.doFirst {
      ((JBossConfigurationTask) jbossConf).configureJBoss()
    }.doLast {
      ((SilverpeasConfigurationTask) silverpeasConf).configureSilverpeas()
    }.dependsOn(construction)

    Task migration = project.tasks.create(MIGRATE.name, SilverpeasMigrationTask) {
      it.migration        = extension.migration
      it.settings         = extension.settings
    }.dependsOn(configuration)

    project.tasks.create(INSTALL.name, SilverpeasInstallationTask) {
      it.installation    = extension.installation
      it.settings        = extension.settings
      it.jboss           = jBossServer
    }.dependsOn(construction, configuration, migration)

    setUpGradleAssemblingTaskForThisPlugin(project, extension)
    setUpGradleBuildTaskForThisPlugin(project, extension)
  }

  private void setUpGradleAssemblingTaskForThisPlugin(Project project,
                                                      SilverpeasSetupExtension extension) {
    try {
      Task assemble = project.tasks.getByName(ASSEMBLE.name).doLast {
        if (!extension.installation.distDir.get().exists()) {
          extension.installation.distDir.get().mkdirs()
        }
        SilverpeasBuilder builder = new SilverpeasBuilder(project, FileLogger.getLogger(delegate.name))
        builder.driversDir = extension.installation.dsDriversDir.get()
        builder.silverpeasHome = extension.silverpeasHome
        builder.settings = extension.settings
        builder.extractSoftwareBundles(extension.installation.bundles,
            extension.installation.distDir.get())
      }
      assemble.description = 'Assemble all the software bundles that made Silverpeas'
      assemble.onlyIf { !extension.installation.distDir.get().exists() &&
          !extension.installation.dsDriversDir.get().exists()}
      assemble.outputs.upToDateWhen {
        extension.installation.distDir.get().exists() &&
            extension.installation.dsDriversDir.get().exists()
      }
    } catch (UnknownTaskException e) {
      // nothing to do
    }
  }

  private void setUpGradleBuildTaskForThisPlugin(Project project,
                                                 SilverpeasSetupExtension extension) {
    try {
      Task build = project.tasks.getByName(BUILD.name).doLast {
        SilverpeasBuilder builder = new SilverpeasBuilder(project, FileLogger.getLogger(delegate.name))
        builder.silverpeasHome = extension.silverpeasHome
        builder.settings = extension.settings
        builder.developmentMode = extension.installation.developmentMode.get()
        builder.generateSilverpeasApplication(extension.installation.distDir.get())
      }
      build.description = 'Build the Silverpeas Collaborative Web Application'
      build.onlyIf {
        extension.installation.distDir.get().exists()
      }
      build.outputs.upToDateWhen {
        boolean ok = extension.installation.distDir.get().exists() &&
            Files.exists(Paths.get(extension.installation.distDir.get().path, 'WEB-INF', 'web.xml'))
        if (!extension.installation.developmentMode.get()) {
          ok = ok && Files.exists(
              Paths.get(project.buildDir.path, SilverpeasConstructionTask.SILVERPEAS_WAR))
        }
        return ok
      }
    }  catch (UnknownTaskException e) {
      // nothing to do
    }
  }

  private void initSilverpeasSetupExtention(SilverpeasSetupExtension silverSetup) {
    silverSetup.settings = loadConfigurationProperties(silverSetup.config.configurationHome.get())
    completeSettings(silverSetup.settings, silverSetup)
    encryptAdminPassword(silverSetup.settings)
  }

  private Map loadConfigurationProperties(File configurationHome) {
    Properties properties = new Properties()
    properties.load(getClass().getResourceAsStream('/default_config.properties'))
    File customConfiguration = new File(configurationHome, 'config.properties')
    // the custom configuration overrides the default configuration
    if (customConfiguration.exists()) {
      Properties customProperties = new Properties()
      customProperties.load(new StringReader(customConfiguration.text.replace('\\', '/')))
      customProperties.propertyNames().each {
        properties[it] = customProperties[it]
      }
    }
    // replace the variables by their values in the properties and return the properties
    return VariableReplacement.parseParameters(properties, properties)
  }

  private void completeSettings(Map settings, SilverpeasSetupExtension silverSetup) {
    settings.SILVERPEAS_HOME = normalizePath(silverSetup.silverpeasHome.path)
    settings.MIGRATION_HOME = normalizePath(silverSetup.migration.homeDir.get().path)
    settings.CONFIGURATION_HOME = normalizePath(silverSetup.config.configurationHome.get().path)
    settings.SILVERPEAS_DATA_HOME = normalizePath(settings.SILVERPEAS_DATA_HOME)
    settings.SILVERPEAS_DATA_WEB = normalizePath(settings.SILVERPEAS_DATA_WEB)
    settings.JCR_HOME = normalizePath(settings.JCR_HOME)
    settings.SILVERPEAS_TEMP = normalizePath(settings.SILVERPEAS_TEMP)
    settings.SILVERPEAS_LOG = normalizePath(settings.SILVERPEAS_LOG)
    settings.HIDDEN_SILVERPEAS_DIR = normalizePath(settings.HIDDEN_SILVERPEAS_DIR)
    settings.DB_DATASOURCE_JNDI = 'java:/datasources/silverpeas'
    switch (settings.DB_SERVERTYPE) {
      case 'MSSQL':
        settings.DB_URL = "jdbc:jtds:sqlserver://${settings.DB_SERVER}:${settings.DB_PORT_MSSQL}/${settings.DB_NAME}"
        settings.JCR_URL = "jdbc:jtds:sqlserver://${settings.DB_SERVER}:${settings.DB_PORT_MSSQL}/${settings.JCR_NAME}"
        settings.DB_DRIVER = 'net.sourceforge.jtds.jdbc.Driver'
        settings.JACKRABBIT_PERSISTENCE_MANAGER = 'org.apache.jackrabbit.core.persistence.pool.MSSqlPersistenceManager'
        break
      case 'ORACLE':
        settings.DB_URL = "jdbc:oracle:thin:@${settings.DB_SERVER}:${settings.DB_PORT_ORACLE}:${settings.DB_NAME}"
        settings.JCR_URL = "jdbc:oracle:thin:@${settings.DB_SERVER}:${settings.DB_PORT_ORACLE}:${settings.JCR_NAME}"
        settings.DB_DRIVER = 'oracle.jdbc.driver.OracleDriver'
        settings.JACKRABBIT_PERSISTENCE_MANAGER = 'org.apache.jackrabbit.core.persistence.pool.OraclePersistenceManager'
        break
      case 'POSTGRESQL':
        settings.DB_URL = "jdbc:postgresql://${settings.DB_SERVER}:${settings.DB_PORT_POSTGRESQL}/${settings.DB_NAME}"
        settings.JCR_URL = "jdbc:postgresql://${settings.DB_SERVER}:${settings.DB_PORT_POSTGRESQL}/${settings.JCR_NAME}"
        settings.DB_DRIVER = 'org.postgresql.Driver'
        settings.JACKRABBIT_PERSISTENCE_MANAGER = 'org.apache.jackrabbit.core.persistence.pool.PostgreSQLPersistenceManager'
        break
      case 'H2':
        if (settings.DB_SERVER == ':file:') {
          Path databaseDirPath = Paths.get(settings.SILVERPEAS_HOME, 'h2')
          if (!Files.exists(databaseDirPath))
            Files.createDirectory(databaseDirPath)
          settings.DB_URL = "jdbc:h2:file:${settings.SILVERPEAS_HOME}/h2/${settings.DB_NAME};MV_STORE=FALSE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
          settings.JCR_URL = "jdbc:h2:file:${settings.SILVERPEAS_HOME}/h2/${settings.JCR_NAME};MV_STORE=FALSE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
        } else {
          settings.DB_URL = "jdbc:h2:tcp://${settings.DB_SERVER}:${settings.DB_PORT_H2}/${settings.DB_NAME}"
          settings.JCR_URL = "jdbc:h2:tcp://${settings.DB_SERVER}:${settings.DB_PORT_H2}/${settings.JCR_NAME}"
        }
        settings.DB_DRIVER = 'org.h2.Driver'
        settings.JACKRABBIT_PERSISTENCE_MANAGER = 'org.apache.jackrabbit.core.persistence.pool.H2PersistenceManager'
        break
      default:
        throw new IllegalArgumentException("Unsupported database system: ${settings.DB_SERVERTYPE}")
    }
    settings.DB_SCHEMA = settings.DB_SERVERTYPE.toLowerCase()
  }

  private void encryptAdminPassword(Map settings) {
    Encryption encryption = EncryptionFactory.instance.createDefaultEncryption()
    settings.SILVERPEAS_ADMIN_PASSWORD = encryption.encrypt(settings.SILVERPEAS_ADMIN_PASSWORD)
  }

  private void initLogging(Project project, SilverpeasLoggingProperties loggingProperties) {
    String timestamp = new Date().format('yyyyMMdd_HHmmss')
    if (!loggingProperties.logDir.exists()) {
      loggingProperties.logDir.mkdirs()
    }
    File logFile = new File(loggingProperties.logDir, "build-${timestamp}.log")
    FileLogger.init(logFile, loggingProperties.defaultLevel)

    /* customize the traces writing both on the standard output and on the log file. */
    project.gradle.useLogger(new TaskEventLogging()
        .withTasks(loggingProperties.scriptTasks))
  }

  private static String normalizePath(String path) {
    return path.replace('\\', '/')
  }
}
