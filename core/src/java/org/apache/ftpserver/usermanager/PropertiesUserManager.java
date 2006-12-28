/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */  

package org.apache.ftpserver.usermanager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ftpserver.ftplet.Authentication;
import org.apache.ftpserver.ftplet.AuthenticationFailedException;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.Configuration;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.util.BaseProperties;
import org.apache.ftpserver.util.EncryptUtils;
import org.apache.ftpserver.util.IoUtils;


/**
 * Properties file based <code>UserManager</code> implementation. 
 * We use <code>user.properties</code> file to store user data.
 * 
 * @author <a href="mailto:rana_b@yahoo.com">Rana Bhattacharyya</a>
 */
public
class PropertiesUserManager extends AbstractUserManager {

    private final static String PREFIX    = "FtpServer.user.";

    private Log log;
    
    private BaseProperties userDataProp;
    private File           userDataFile;
    private boolean        isPasswordEncrypt;
    private String         adminName;
    
    
    /**
     * Set the log factory.
     */
    public void setLogFactory(LogFactory factory) {
        log = factory.getInstance(getClass());
    } 
    
    /**
     * Configure user manager.
     */
    public void configure(Configuration config) throws FtpException {
        try {
            userDataFile = new File(config.getString("prop-file", "./res/user.gen"));
            File dir = userDataFile.getParentFile();
            if( (!dir.exists()) && (!dir.mkdirs()) ) {
                String dirName = dir.getAbsolutePath();
                throw new IOException("Cannot create directory : " + dirName);
            }
            userDataFile.createNewFile();
            userDataProp = new BaseProperties(userDataFile);
            
            isPasswordEncrypt = config.getBoolean("prop-password-encrypt", true);
            adminName = config.getString("admin", "admin");
        }
        catch(IOException ex) {
            log.fatal("PropertiesUserManager.configure()", ex);
            throw new FtpException("PropertiesUserManager.configure()", ex);
        }
    }

    /**
     * Get the admin name.
     */
    public String getAdminName() {
        return adminName;
    }
    
    /**
     * @return true if user with this login is administrator
     */
    public boolean isAdmin(String login) throws FtpException {
        return adminName.equals(login);
    }
    
    /**
     * Save user data. Store the properties.
     */
    public synchronized void save(User usr) throws FtpException {
        
       // null value check
       if(usr.getName() == null) {
           throw new NullPointerException("User name is null.");
       }
       String thisPrefix = PREFIX + usr.getName() + '.';
       
       // set other properties
       userDataProp.setProperty(thisPrefix + ATTR_PASSWORD,          getPassword(usr));
       
       String home = usr.getHomeDirectory();
       if(home == null) {
           home = "/";
       }
       userDataProp.setProperty(thisPrefix + ATTR_HOME,              home);
       userDataProp.setProperty(thisPrefix + ATTR_ENABLE,            usr.getEnabled());
       userDataProp.setProperty(thisPrefix + ATTR_WRITE_PERM,        usr.authorize(new WriteRequest()));
       userDataProp.setProperty(thisPrefix + ATTR_MAX_IDLE_TIME,     usr.getMaxIdleTime());
       userDataProp.setProperty(thisPrefix + ATTR_MAX_UPLOAD_RATE,   usr.getMaxUploadRate());
       userDataProp.setProperty(thisPrefix + ATTR_MAX_DOWNLOAD_RATE, usr.getMaxDownloadRate());
       userDataProp.setProperty(thisPrefix + ATTR_MAX_LOGIN_NUMBER, usr.getMaxLoginNumber());
       userDataProp.setProperty(thisPrefix + ATTR_MAX_LOGIN_PER_IP, usr.getMaxLoginPerIP());
   
       saveUserData();
    }

    /**
     * @throws FtpException
     */
    private void saveUserData() throws FtpException {
        // save user data
           FileOutputStream fos = null;
           try {
               fos = new FileOutputStream(userDataFile);
               userDataProp.store(fos, "Generated file - don't edit (please)");
           }
           catch(IOException ex) {
               log.error("Failed saving user data", ex);
               throw new FtpException("Failed saving user data", ex);
           }
           finally {
               IoUtils.close(fos);
           }
    }
     
    /**
     * Delete an user. Removes all this user entries from the properties.
     * After removing the corresponding from the properties, save the data.
     */
    public synchronized void delete(String usrName) throws FtpException {
        
        // remove entries from properties
        String thisPrefix = PREFIX + usrName + '.';
        Enumeration propNames = userDataProp.propertyNames();
        ArrayList remKeys = new ArrayList();
        while(propNames.hasMoreElements()) {
            String thisKey = propNames.nextElement().toString();
            if(thisKey.startsWith(thisPrefix)) {
                remKeys.add(thisKey);
            }
        }
        Iterator remKeysIt = remKeys.iterator();
        while (remKeysIt.hasNext()) {
            userDataProp.remove(remKeysIt.next().toString());
        }
        
        saveUserData();
    }
    
    /**
     * Get user password. Returns the encrypted value.
     * <pre>
     * If the password value is not null
     *    password = new password 
     * else 
     *   if user does exist
     *     password = old password
     *   else 
     *     password = ""
     * </pre>
     */
    private String getPassword(User usr) {
        String name = usr.getName();
        String password = usr.getPassword();
        
        if(password != null) {
            if (isPasswordEncrypt) {
                password = EncryptUtils.encryptMD5(password);
            }
        }
        else {
            String blankPassword = "";
            if(isPasswordEncrypt) {
                blankPassword = EncryptUtils.encryptMD5("");
            }
            
            if( doesExist(name) ) {
                String key = PREFIX + name + '.' + ATTR_PASSWORD;
                password = userDataProp.getProperty(key, blankPassword);
            }
            else {
                password = blankPassword;
            }
        }
        return password;
    } 
    
    /**
     * Get all user names.
     */
    public synchronized String[] getAllUserNames() {

        // get all user names
        String suffix = '.' + ATTR_HOME;
        ArrayList ulst = new ArrayList();
        Enumeration allKeys = userDataProp.propertyNames();
        int prefixlen = PREFIX.length();
        int suffixlen = suffix.length();
        while(allKeys.hasMoreElements()) {
            String key = (String)allKeys.nextElement();
            if(key.endsWith(suffix)) {
                String name = key.substring(prefixlen);
                int endIndex = name.length() - suffixlen;
                name = name.substring(0, endIndex);
                ulst.add(name);
            }
        }
        
        Collections.sort(ulst);
        return (String[]) ulst.toArray(new String[0]);
    }

    /**
     * Load user data.
     */
    public synchronized User getUserByName(String userName) {
        
        if (!doesExist(userName)) {
            return null;
        }
        
        String baseKey = PREFIX + userName + '.';
        BaseUser user = new BaseUser();
        user.setName(userName);
        user.setEnabled(userDataProp.getBoolean(baseKey + ATTR_ENABLE, true));
        user.setHomeDirectory( userDataProp.getProperty(baseKey + ATTR_HOME, "/") );
        
        List authorities = new ArrayList();
        
        if(userDataProp.getBoolean(baseKey + ATTR_WRITE_PERM, false)) {
            authorities.add(new WritePermission());
        }
        
        user.setAuthorities((Authority[]) authorities.toArray(new Authority[0]));
        
        //user.setWritePermission(userDataProp.getBoolean(baseKey + ATTR_WRITE_PERM, false));
        user.setMaxLoginNumber(userDataProp.getInteger(baseKey + ATTR_MAX_LOGIN_NUMBER, 0));
        user.setMaxLoginPerIP(userDataProp.getInteger(baseKey + ATTR_MAX_LOGIN_PER_IP, 0));
        user.setMaxIdleTime(userDataProp.getInteger(baseKey + ATTR_MAX_IDLE_TIME, 0));
        user.setMaxUploadRate(userDataProp.getInteger(baseKey + ATTR_MAX_UPLOAD_RATE, 0));
        user.setMaxDownloadRate(userDataProp.getInteger(baseKey + ATTR_MAX_DOWNLOAD_RATE, 0));
        return user;
    }
    
    /**
     * User existance check
     */
    public synchronized boolean doesExist(String name) {
        String key = PREFIX + name + '.' + ATTR_HOME;
        return userDataProp.containsKey(key);
    }
    
    /**
     * User authenticate method
     */
    public synchronized User authenticate(Authentication authentication) throws AuthenticationFailedException {
        
        if(authentication instanceof UsernamePasswordAuthentication) {
            UsernamePasswordAuthentication upauth = (UsernamePasswordAuthentication) authentication;
            
            String user = upauth.getUsername(); 
            String password = upauth.getPassword(); 
        
            if(user == null) {
                throw new AuthenticationFailedException("Authentication failed");
            }
            
            if(password == null) {
                password = "";
            }
            
            String passVal = userDataProp.getProperty(PREFIX + user + '.' + ATTR_PASSWORD);
            if (isPasswordEncrypt) {
                password = EncryptUtils.encryptMD5(password);
            }
            if(password.equals(passVal)) {
                return getUserByName(user);
            } else {
                throw new AuthenticationFailedException("Authentication failed");
            }
            
        } else if(authentication instanceof AnonymousAuthentication) {
            if(doesExist("anonymous")) {
                return getUserByName("anonymous");
            } else {
                throw new AuthenticationFailedException("Authentication failed");
            }
        } else {
            throw new IllegalArgumentException("Authentication not supported by this user manager");
        }
    }
        
    /**
     * Close the user manager - remove existing entries.
     */
    public synchronized void dispose() {
        if (userDataProp != null) {
            userDataProp.clear();
            userDataProp = null;
        }
    }
}

