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
package org.syncany.operations.init;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;
import org.syncany.config.Config;
import org.syncany.config.to.ConfigTO;
import org.syncany.config.to.ConfigTO.ConnectionTO;
import org.syncany.config.to.MasterTO;
import org.syncany.config.to.RepoTO;
import org.syncany.crypto.CipherException;
import org.syncany.crypto.CipherUtil;
import org.syncany.crypto.SaltedSecretKey;
import org.syncany.operations.init.ConnectOperationOptions.ConnectOptionsStrategy;
import org.syncany.operations.init.ConnectOperationResult.ConnectResultCode;
import org.syncany.plugins.Plugins;
import org.syncany.plugins.UserInteractionListener;
import org.syncany.plugins.transfer.StorageException;
import org.syncany.plugins.transfer.StorageTestResult;
import org.syncany.plugins.transfer.TransferManager;
import org.syncany.plugins.transfer.TransferPlugin;
import org.syncany.plugins.transfer.TransferSettings;
import org.syncany.plugins.transfer.files.MasterRemoteFile;
import org.syncany.plugins.transfer.files.RemoteFile;
import org.syncany.plugins.transfer.files.SyncanyRemoteFile;
import org.syncany.util.Base58;

/**
 * The connect operation connects to an existing repository at a given remote storage
 * location. Its responsibilities include:
 * 
 * <ul>
 *   <li>Downloading of the repo file. If it is encrypted, also downloading the master
 *       file to allow decrypting the repo file.</li>
 *   <li>If encrypted: Querying the user for the password and creating the master key using
 *       the password and the master salt.</li>
 *   <li>If encrypted: Decrypting and verifying the repo file.</li>
 *   <li>Creating the local Syncany folder structure in the local directory (.syncany 
 *       folder and the sub-structure) and copying the repo/master file to it.</li>
 * </ul> 
 *   
 * @author Philipp C. Heckel <philipp.heckel@gmail.com>
 */
public class ConnectOperation extends AbstractInitOperation {
	private static final Logger logger = Logger.getLogger(ConnectOperation.class.getSimpleName());		
	
	private static final Pattern LINK_PATTERN = Pattern.compile("^syncany://storage/1/(?:(not-encrypted/)(.+)|([^-]+)/(.+))$");
	private static final int LINK_PATTERN_GROUP_NOT_ENCRYPTED_FLAG = 1;
	private static final int LINK_PATTERN_GROUP_NOT_ENCRYPTED_ENCODED = 2;
	private static final int LINK_PATTERN_GROUP_ENCRYPTED_MASTER_KEY_SALT = 3;
	private static final int LINK_PATTERN_GROUP_ENCRYPTED_ENCODED = 4;

	private static final int MAX_RETRY_PASSWORD_COUNT = 3;
	private int retryPasswordCount = 0;

	private ConnectOperationOptions options;
	private ConnectOperationResult result;
	private UserInteractionListener listener;

	private TransferPlugin plugin;
	private TransferManager transferManager;

	public ConnectOperation(ConnectOperationOptions options, UserInteractionListener listener) {
		super(null, null);

		this.options = options;
		this.result = new ConnectOperationResult();
		this.listener = listener;
	}

	@Override
	public ConnectOperationResult execute() throws Exception {
		logger.log(Level.INFO, "");
		logger.log(Level.INFO, "Running 'Connect'");
		logger.log(Level.INFO, "--------------------------------------------");

		// Decrypt and init configTO
		ConfigTO configTO = null;

		try {
			configTO = createConfigTO();
		}
		catch (CipherException e) {
			return new ConnectOperationResult(ConnectResultCode.NOK_DECRYPT_ERROR);
		}

		// Init plugin and transfer manager
		plugin = Plugins.get(options.getConfigTO().getConnectionTO().getType(), TransferPlugin.class);

		TransferSettings connection = plugin.createSettings();

		connection.init(options.getConfigTO().getConnectionTO().getSettings());
		connection.setUserInteractionListener(listener);

		transferManager = plugin.createTransferManager(connection, null);

		// Test the repo
		if (!performRepoTest(transferManager)) {
			logger.log(Level.INFO, "- Connecting to the repo failed, repo already exists or cannot be created: " + result.getResultCode());
			return result;
		}

		logger.log(Level.INFO, "- Connecting to the repo was successful; now downloading repo file ...");

		// Create local .syncany directory
		File tmpRepoFile = downloadFile(transferManager, new SyncanyRemoteFile());

		if (CipherUtil.isEncrypted(tmpRepoFile)) {
			logger.log(Level.INFO, "- Repo is ENCRYPTED. Decryption necessary.");

			if (configTO.getMasterKey() == null) {
				logger.log(Level.INFO, "- No master key present; Asking for password ...");

				boolean retryPassword = true;

				while (retryPassword) {
					SaltedSecretKey possibleMasterKey = askPasswordAndCreateMasterKey();
					logger.log(Level.INFO, "- Master key created. Now verifying by decrypting repo file...");

					if (decryptAndVerifyRepoFile(tmpRepoFile, possibleMasterKey)) {
						logger.log(Level.INFO, "- SUCCESS: Repo file decrypted successfully.");

						configTO.setMasterKey(possibleMasterKey);
						retryPassword = false;
					}
					else {
						logger.log(Level.INFO, "- FAILURE: Repo file decryption failed. Asking for retry.");
						retryPassword = askRetryPassword();

						if (!retryPassword) {
							logger.log(Level.INFO, "- No retry possible/desired. Returning NOK_DECRYPT_ERROR.");
							return new ConnectOperationResult(ConnectResultCode.NOK_DECRYPT_ERROR);
						}
					}
				}
			}
			else {
				logger.log(Level.INFO, "- Master key present; Now verifying by decrypting repo file...");

				if (!decryptAndVerifyRepoFile(tmpRepoFile, configTO.getMasterKey())) {
					logger.log(Level.INFO, "- FAILURE: Repo file decryption failed. Returning NOK_DECRYPT_ERROR.");
					return new ConnectOperationResult(ConnectResultCode.NOK_DECRYPT_ERROR);
				}
			}
		}
		else {
			String repoFileStr = FileUtils.readFileToString(tmpRepoFile);
			verifyRepoFile(repoFileStr);
		}

		// Success, now do the work!
		File appDir = createAppDirs(options.getLocalDir());

		// Write file 'config.xml'
		File configFile = new File(appDir, Config.FILE_CONFIG);
		writeXmlFile(configTO, configFile);

		// Write file 'syncany'
		File repoFile = new File(appDir, Config.FILE_REPO);
		FileUtils.copyFile(tmpRepoFile, repoFile);
		tmpRepoFile.delete();

		// Write file 'master'
		if (configTO.getMasterKey() != null) {
			File masterFile = new File(appDir, Config.FILE_MASTER);
			writeXmlFile(new MasterTO(configTO.getMasterKey().getSalt()), masterFile);
		}

		// Shutdown plugin
		transferManager.disconnect();

		// Add to daemon (if requested)
		if (options.isDaemon()) {
			boolean addedToDaemonConfig = addToDaemonConfig(options.getLocalDir());
			result.setAddedToDaemon(addedToDaemonConfig);
		}
		
		result.setResultCode(ConnectResultCode.OK);
		return result;
	}		

	private boolean decryptAndVerifyRepoFile(File tmpRepoFile, SaltedSecretKey masterKey) throws StorageException {
		try {
			String repoFileStr = decryptRepoFile(tmpRepoFile, masterKey);
			verifyRepoFile(repoFileStr);

			return true;
		}
		catch (CipherException e) {
			return false;
		}
	}

	private SaltedSecretKey askPasswordAndCreateMasterKey() throws CipherException, StorageException {
		File tmpMasterFile = downloadFile(transferManager, new MasterRemoteFile());
		MasterTO masterTO = readMasterFile(tmpMasterFile);

		tmpMasterFile.delete();

		String masterKeyPassword = getOrAskPassword();
		byte[] masterKeySalt = masterTO.getSalt();

		return createMasterKeyFromPassword(masterKeyPassword, masterKeySalt); // This takes looong!
	}

	private ConfigTO createConfigTO() throws StorageException, CipherException {
		ConfigTO configTO = options.getConfigTO();

		if (options.getStrategy() == ConnectOptionsStrategy.CONNECTION_TO) {
			return configTO;
		}
		else if (options.getStrategy() == ConnectOptionsStrategy.CONNECTION_LINK) {
			return createConfigTOFromLink(configTO, options.getConnectLink());
		}
		else {
			throw new RuntimeException("Unhandled connect strategy: " + options.getStrategy());
		}
	}

	private ConfigTO createConfigTOFromLink(ConfigTO configTO, String link) throws StorageException, CipherException {
		Matcher linkMatcher = LINK_PATTERN.matcher(link);

		if (!linkMatcher.matches()) {
			throw new StorageException("Invalid link provided, must start with syncany:// and match link pattern.");
		}

		String notEncryptedFlag = linkMatcher.group(LINK_PATTERN_GROUP_NOT_ENCRYPTED_FLAG);

		String plaintext = null;
		boolean isEncryptedLink = notEncryptedFlag == null;

		if (isEncryptedLink) {
			String masterKeySaltStr = linkMatcher.group(LINK_PATTERN_GROUP_ENCRYPTED_MASTER_KEY_SALT);
			String ciphertext = linkMatcher.group(LINK_PATTERN_GROUP_ENCRYPTED_ENCODED);
			
			byte[] masterKeySalt = Base58.decode(masterKeySaltStr);
			byte[] ciphertextBytes = Base58.decode(ciphertext);

			boolean retryPassword = true;

			while (retryPassword) {
				// Ask password
				String masterPassword = getOrAskPassword();

				// Generate master key
				SaltedSecretKey masterKey = createMasterKeyFromPassword(masterPassword, masterKeySalt);
				configTO.setMasterKey(masterKey);

				// Decrypt config
				try {
					ByteArrayInputStream encryptedStorageConfig = new ByteArrayInputStream(ciphertextBytes);
					plaintext = new String(CipherUtil.decrypt(encryptedStorageConfig, masterKey));

					retryPassword = false;
				}
				catch (CipherException e) {
					retryPassword = askRetryPassword();
				}
			}

			if (plaintext == null) {
				throw new CipherException("Unable to decrypt link.");
			}
		}
		else {
			String encodedPlaintext = linkMatcher.group(LINK_PATTERN_GROUP_NOT_ENCRYPTED_ENCODED);
			plaintext = new String(Base58.decode(encodedPlaintext));
		}

		try {
			Serializer serializer = new Persister();
			ConnectionTO connectionTO = serializer.read(ConnectionTO.class, plaintext);

			TransferPlugin plugin = Plugins.get(connectionTO.getType(), TransferPlugin.class);

			if (plugin == null) {
				throw new StorageException("Link contains unknown connection type '" + connectionTO.getType() + "'. Corresponding plugin not found.");
			}

			configTO.setConnectionTO(connectionTO);
		}
		catch (Exception e) {
			throw new StorageException(e);
		}

		return configTO;
	}

	private boolean performRepoTest(TransferManager transferManager) {
		StorageTestResult testResult = transferManager.test(false);

		logger.log(Level.INFO, "Storage test result ist " + testResult);

		if (testResult.isRepoFileExists()) {
			logger.log(Level.INFO, "--> OKAY: Repo file exists. We're good to go!");
			return true;
		}
		else {
			logger.log(Level.INFO, "--> NOT OKAY: Invalid target/repo state. Operation cannot be continued.");
			
			result.setResultCode(ConnectResultCode.NOK_TEST_FAILED);
			result.setTestResult(testResult);
			
			return false;
		}
	}

	private String getOrAskPassword() {
		if (options.getPassword() == null) {
			if (listener == null) {
				throw new RuntimeException("Repository file is encrypted, but password cannot be queried (no listener).");
			}

			return listener.onUserPassword(null, "Password: ");
		}
		else {
			return options.getPassword();
		}
	}

	private boolean askRetryPassword() {
		retryPasswordCount++;

		if (retryPasswordCount < MAX_RETRY_PASSWORD_COUNT) {
			int triesLeft = MAX_RETRY_PASSWORD_COUNT - retryPasswordCount;
			String triesLeftStr = triesLeft != 1 ? triesLeft + " tries left." : "Last chance.";

			listener.onShowMessage("ERROR: Invalid password or corrupt ciphertext. " + triesLeftStr);
			return true;
		}
		else {
			return false;
		}
	}

	protected File downloadFile(TransferManager transferManager, RemoteFile remoteFile) throws StorageException {
		try {
			File tmpRepoFile = File.createTempFile("syncanyfile", "tmp");

			transferManager.download(remoteFile, tmpRepoFile);
			return tmpRepoFile;
		}
		catch (Exception e) {
			throw new StorageException("Unable to connect to repository.", e);
		}
	}

	private SaltedSecretKey createMasterKeyFromPassword(String masterPassword, byte[] masterKeySalt) throws CipherException {
		fireNotifyCreateMaster();

		SaltedSecretKey masterKey = CipherUtil.createMasterKey(masterPassword, masterKeySalt);
		return masterKey;
	}

	private String decryptRepoFile(File file, SaltedSecretKey masterKey) throws CipherException {
		try {
			logger.log(Level.INFO, "Decrypting repo file ...");

			FileInputStream encryptedRepoConfig = new FileInputStream(file);
			String repoFileStr = new String(CipherUtil.decrypt(encryptedRepoConfig, masterKey));

			logger.log(Level.INFO, "Repo file decrypted:");
			logger.log(Level.INFO, repoFileStr);

			return repoFileStr;
		}
		catch (Exception e) {
			logger.log(Level.INFO, "Invalid password given, or repo file corrupt.", e);
			throw new CipherException("Invalid password given, or repo file corrupt.", e);
		}
	}

	private void verifyRepoFile(String repoFileStr) throws StorageException {
		try {
			Serializer serializer = new Persister();
			serializer.read(RepoTO.class, repoFileStr);
		}
		catch (Exception e) {
			throw new StorageException("Repo file corrupt.", e);
		}
	}

	private MasterTO readMasterFile(File tmpMasterFile) throws StorageException {
		try {
			Serializer serializer = new Persister();
			return serializer.read(MasterTO.class, tmpMasterFile);
		}
		catch (Exception e) {
			throw new StorageException("Master file corrupt.", e);
		}
	}
}
