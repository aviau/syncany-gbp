/*
 * Syncany, www.syncany.org
 * Copyright (C) 2011-2014 Philipp C. Heckel <philipp.heckel@gmail.com> 
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.syncany.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;

import org.syncany.config.to.DaemonConfigTO;
import org.syncany.config.to.FolderTO;
import org.syncany.config.to.UserConfigTO;
import org.syncany.config.to.UserTO;
import org.syncany.crypto.CipherUtil;
import org.syncany.operations.watch.WatchOperationOptions;
import org.syncany.util.EnvironmentUtil;

/**
 * Represents the configuration parameters and application user directory
 * of the currently logged in user, including system properties that will be 
 * set with every application start. 
 *  
 * @author Philipp C. Heckel <philipp.heckel@gmail.com>
 */
public class UserConfig {	
	/* 
	 * Note: 
	 *   This class can't have any logging methods, because the init() method is called 
	 *   BEFORE the logging initialization. All errors must be printed to STDERR.
	 *    
	 */
	
	// Daemon-specific config
	public static final String DAEMON_FILE = "daemon.xml";
	public static final String DAEMON_EXAMPLE_FILE = "daemon-example.xml";	
	public static final String DEFAULT_FOLDER = "Syncany";
	public static final String USER_ADMIN = "admin";
	public static final String USER_CLI = "CLI";
	
	// These fields are not final to enable a PluginOperationTest
	private static File USER_APP_DIR_WINDOWS = new File(System.getenv("APPDATA") + "\\Syncany");
	private static File USER_APP_DIR_UNIX_LIKE = new File(System.getProperty("user.home") + "/.config/syncany");
		
	private static final String USER_PLUGINS_LIB_DIR = "plugins/lib";
	private static final String USER_PLUGINS_USERDATA_DIR_FORMAT = "plugins/userdata/%s";
	private static final String USER_CONFIG_FILE = "userconfig.xml";
	private static final String USER_TRUSTSTORE_FILE = "truststore.jks";
	private static final String USER_KEYSTORE_FILE = "keystore.jks";	
	
	private static File userConfigDir;
	private static File userPluginLibDir;
	private static File userConfigFile;
	
	private static File userTrustStoreFile;
	private static KeyStore userTrustStore;	
	
	private static File userKeyStoreFile;
	private static KeyStore userKeyStore;	

	private static boolean preventStandby;
	
	static {
		init();
	}
	
	public static void init() {
		if (userConfigDir == null) {
			initUserAppDirs();	
			initUserConfig();
			initUserTrustStore();
			initUserKeyStore();
		}
	}

	public static File getUserConfigDir() { 
		return userConfigDir;
	}

	public static File getUserPluginLibDir() {
		return userPluginLibDir;
	}
	
	public static File getUserPluginsUserdataDir(String pluginId) {
		File pluginConfigDir = new File(userConfigDir, String.format(USER_PLUGINS_USERDATA_DIR_FORMAT, pluginId));
		pluginConfigDir.mkdirs();

		return pluginConfigDir;
	}
	
	public static File getUserConfigFile() {
		return userConfigFile;
	}	

	public static boolean preventStandbyEnabled() {
		return preventStandby;
	}
	
	public static KeyStore getUserTrustStore() {
		// Note: This method might not be used by the main project modules,
		//       but it might be used by plugins. Do not remove unless you are
		//       sure that it is not needed.
		
		return userTrustStore;
	}
	
	public static KeyStore getUserKeyStore() {
		return userKeyStore;
	}
	
	public static void storeTrustStore() {
		storeKeyStore(userTrustStore, userTrustStoreFile);
	}
	
	public static void storeUserKeyStore() {
		storeKeyStore(userKeyStore, userKeyStoreFile);
	}
	
	public static SSLContext createUserSSLContext() throws Exception {
		return CipherUtil.createSSLContext(userKeyStore, userTrustStore);
	}
	
	// General initialization methods
	
	private static void initUserAppDirs() {
		userConfigDir = (EnvironmentUtil.isWindows()) ? USER_APP_DIR_WINDOWS : USER_APP_DIR_UNIX_LIKE;
		userConfigDir.mkdirs();
		
		userPluginLibDir = new File(userConfigDir, USER_PLUGINS_LIB_DIR);		
		userPluginLibDir.mkdirs();
	}

	private static void initUserConfig() {
		userConfigFile = new File(userConfigDir, USER_CONFIG_FILE);
		
		if (userConfigFile.exists()) {
			loadAndInitUserConfigFile(userConfigFile);			
		}
		else {
			writeExampleUserConfigFile(userConfigFile);
		}
	}

	private static void loadAndInitUserConfigFile(File userConfigFile) {
		try {
			UserConfigTO userConfigTO = UserConfigTO.load(userConfigFile);
			
			// System properties
			for (Map.Entry<String, String> systemProperty : userConfigTO.getSystemProperties().entrySet()) {
				System.setProperty(systemProperty.getKey(), systemProperty.getValue());
			}
			
			// Other options
			preventStandby = userConfigTO.preventStandbyEnabled();
		}
		catch (ConfigException e) {
			System.err.println("ERROR: " + e.getMessage());
			System.err.println("       Ignoring user config file!");
			System.err.println();
		}
	}	

	private static void writeExampleUserConfigFile(File userConfigFile) {
		UserConfigTO userConfigTO = new UserConfigTO();
		
		userConfigTO.getSystemProperties().put("example.property", "This is a demo property. You can delete it.");
		userConfigTO.getSystemProperties().put("syncany.rocks", "Yes, it does!");
		
		try {
			UserConfigTO.save(userConfigTO, userConfigFile);
		}
		catch (Exception e) {
			// Don't care!
		}
	}
	
	// Daemon XML config methods

    public static DaemonConfigTO createAndWriteDefaultDaemonConfig(File daemonConfigFile) {
		return createAndWriteDaemonConfig(daemonConfigFile, new ArrayList<FolderTO>());
	}

    public static DaemonConfigTO createAndWriteExampleDaemonConfig(File daemonConfigFile) {
    	File defaultFolder = new File(System.getProperty("user.home"), UserConfig.DEFAULT_FOLDER);		
    	
    	FolderTO defaultFolderTO = new FolderTO();
    	defaultFolderTO.setPath(defaultFolder.getAbsolutePath());
    	defaultFolderTO.setWatchOptions(new WatchOperationOptions());
		
    	return createAndWriteDaemonConfig(daemonConfigFile, Arrays.asList(new FolderTO[] { defaultFolderTO }));
	}

    public static DaemonConfigTO createAndWriteDaemonConfig(File configFile, List<FolderTO> folders) {
    	UserTO defaultUserTO = new UserTO();
		defaultUserTO.setUsername(UserConfig.USER_ADMIN);
		defaultUserTO.setPassword(CipherUtil.createRandomAlphabeticString(12));
		
		ArrayList<UserTO> users = new ArrayList<>();
		users.add(defaultUserTO);
		
		DaemonConfigTO defaultDaemonConfigTO = new DaemonConfigTO();
		defaultDaemonConfigTO.setFolders(new ArrayList<>(folders));	
		defaultDaemonConfigTO.setUsers(users);
		
		try {
			DaemonConfigTO.save(defaultDaemonConfigTO, configFile);
		}
		catch (Exception e) {
			// Don't care!
		}
		
		return defaultDaemonConfigTO;
	}
	
	// Key store / Trust store methods
	
	private static void initUserTrustStore() {
		userTrustStoreFile = new File(userConfigDir, USER_TRUSTSTORE_FILE);
		userTrustStore = initKeyStore(userTrustStoreFile);
	}
	
	private static void initUserKeyStore() {
		userKeyStoreFile = new File(userConfigDir, USER_KEYSTORE_FILE);
		userKeyStore = initKeyStore(userKeyStoreFile);			
	}
	
	private static KeyStore initKeyStore(File keyStoreFile) {
		try {				
			KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
								
			if (keyStoreFile.exists()) {
				FileInputStream trustStoreInputStream = new FileInputStream(keyStoreFile); 		 		
				keyStore.load(trustStoreInputStream, new char[0]);
				
				trustStoreInputStream.close();
			}	
			else {
				keyStore.load(null, new char[0]); // Initialize empty store						
			}
			
			return keyStore;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	private static void storeKeyStore(KeyStore keyStore, File keyStoreFile) {
		try {
			FileOutputStream trustStoreOutputStream = new FileOutputStream(keyStoreFile);
			keyStore.store(trustStoreOutputStream, new char[0]);
			
			trustStoreOutputStream.close();
		}
		catch (Exception e) {
			throw new RuntimeException("Cannot store key/truststore to file " + keyStoreFile, e);
		}		
	}
}
