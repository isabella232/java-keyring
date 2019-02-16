/*
 * Copyright © 2017, Rex Hoffman
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the <organization> nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.east301.keyring.windows;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;

import net.east301.keyring.KeyringBackend;
import net.east301.keyring.PasswordRetrievalException;
import net.east301.keyring.PasswordSaveException;
import net.east301.keyring.util.FileBasedLock;
import net.east301.keyring.util.LockException;

/**
 * Keyring backend which uses Windows DPAPI.
 */
public class WindowsDpApiBackend extends KeyringBackend {

  /**
   * Returns true when the backend is supported.
   */
  @Override
  public boolean isSupported() {
    return Platform.isWindows();
  }

  /**
   * Returns true if the backend directory uses some file to store passwords.
   */
  @Override
  public boolean isKeyStorePathRequired() {
    return true;
  }

  /**
   * Gets password from key store.
   *
   * @param service
   *          Service name
   * @param account
   *          Account name
   *
   * @return Password related to specified service and account
   *
   * @throws PasswordRetrievalException
   *           Thrown when an error happened while getting password
   */
  @Override
  public String getPassword(String service, String account) throws LockException, PasswordRetrievalException {

    FileBasedLock fileLock = new FileBasedLock(getLockPath());

    try {
      //
      fileLock.lock();

      //
      PasswordEntry targetEntry = null;

      for (PasswordEntry entry : loadPasswordEntries()) {
        if (entry.getService().equals(service) && entry.getAccount().equals(account)) {
          targetEntry = entry;
          break;
        }
      }

      if (targetEntry == null) {
        throw new PasswordRetrievalException("Password related to the specified service and account is not found");
      }

      //
      byte[] decryptedBytes;

      try {
        decryptedBytes = Crypt32Util.cryptUnprotectData(targetEntry.getPassword());
      } catch (Exception ex) {
        throw new PasswordRetrievalException("Failed to decrypt password");
      }

      //
      try {
        return new String(decryptedBytes, "UTF-8");
      } catch (UnsupportedEncodingException ex) {
        throw new PasswordRetrievalException("Unsupported encoding 'UTF-8' specified");
      }

    } finally {
      try {
        fileLock.release();
      } catch (Exception ex) {
        Logger.getLogger(WindowsDpApiBackend.class.getName()).log(Level.SEVERE, null, ex);
      }
    }
  }

  /**
   * Sets password to key store.
   *
   * @param service
   *          Service name
   * @param account
   *          Account name
   * @param password
   *          Password
   *
   * @throws PasswordSaveException
   *           Thrown when an error happened while saving the password
   */
  @Override
  public void setPassword(String service, String account, String password) throws LockException, PasswordSaveException {

    FileBasedLock fileLock = new FileBasedLock(getLockPath());

    try {
      //
      fileLock.lock();

      //
      byte[] encryptedBytes;

      try {
        encryptedBytes = Crypt32Util.cryptProtectData(password.getBytes("UTF-8"));
      } catch (UnsupportedEncodingException ex) {
        throw new PasswordSaveException("Unsupported encoding 'UTF-8' specified");
      } catch (Exception ex) {
        throw new PasswordSaveException("Failed to encrypt password");
      }

      //
      List<PasswordEntry> entries = loadPasswordEntries();
      PasswordEntry targetEntry = null;

      for (PasswordEntry entry : entries) {
        if (entry.getService().equals(service) && entry.getAccount().equals(account)) {
          targetEntry = entry;
          break;
        }
      }

      if (targetEntry != null) {
        targetEntry.setPassword(encryptedBytes);
      } else {
        entries.add(new PasswordEntry(service, account, encryptedBytes));
      }

      //
      savePasswordEnetires(entries);
    } finally {
      try {
        fileLock.release();
      } catch (Exception ex) {
        Logger.getLogger(WindowsDpApiBackend.class.getName()).log(Level.SEVERE, null, ex);
      }
    }
  }

  /**
   * Gets backend ID.
   */
  @Override
  public String getId() {
    return "WindowsDPAPI";
  }

  /**
   * Returns path to a file for lock.
   * 
   * @return the key store lock file location.
   */
  public String getLockPath() {
    return keyStorePath + ".lock";
  }

  /**
   * Loads password entries to a file. This method is not thread/process safe.
   */
  private List<PasswordEntry> loadPasswordEntries() {
    ArrayList<PasswordEntry> entries = new ArrayList<>();

    try {
      ObjectInputStream fin = new ObjectInputStream(new FileInputStream(keyStorePath));
      try {
        entries.addAll(Arrays.asList((PasswordEntry[]) fin.readObject()));
      } finally {
        fin.close();
      }
    } catch (Exception ex) {
      Logger.getLogger(WindowsDpApiBackend.class.getName()).log(Level.SEVERE, null, ex);
    }

    return entries;
  }

  /**
   * Saves password entries to a file This method is not thread/process safe.
   *
   * @param entries
   *          Password entries to be saved
   *
   * @throws PasswordSaveException
   *           Thrown when an error happened while writing to a file
   */
  private void savePasswordEnetires(List<PasswordEntry> entries) throws PasswordSaveException {

    try {
      ObjectOutputStream fout = new ObjectOutputStream(new FileOutputStream(keyStorePath));
      try {
        fout.writeObject(entries.toArray(new PasswordEntry[0]));
        fout.flush();
      } finally {
        fout.close();
      }
    } catch (Exception ex) {
      Logger.getLogger(WindowsDpApiBackend.class.getName()).log(Level.SEVERE, null, ex);
      throw new PasswordSaveException("Failed to save password entries to a file");
    }
  }

} // class WindowsDPAPIBackend
