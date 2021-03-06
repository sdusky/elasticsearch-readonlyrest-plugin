package org.elasticsearch.plugin.readonlyrest;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.plugin.readonlyrest.settings.RorSettings;
import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.Key;
import java.security.PrivilegedAction;
import java.security.cert.Certificate;
import java.util.Base64;

/**
 * Created by sscarduzio on 02/07/2017.
 */
public class SSLCertParser {
  private final SSLContextCreator creator;
  private final Logger logger;

  public SSLCertParser(RorSettings settings, ESContext esContext, SSLContextCreator creator) {
    this.creator = creator;
    this.logger = esContext.logger(getClass());
    createContext(settings);
  }

  private void createContext(RorSettings settings) {
    if(!settings.isSSLEnabled()){
      logger.info("SSL is disabled");
      return;
    }
    logger.info("SSL: attempting with JKS keystore..");
    try {
      char[] keyStorePassBa = null;
      if (settings.getKeystorePass().isPresent()) {
        keyStorePassBa = settings.getKeystorePass().get().toCharArray();
      }

      // Load the JKS keystore
      java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
      ks.load(new FileInputStream(settings.getKeystoreFile()), keyStorePassBa);

      char[] keyPassBa = null;
      if (settings.getKeyPass().isPresent()) {
        keyPassBa = settings.getKeyPass().get().toCharArray();
      }

      // Get PrivKey from keystore
      String sslKeyAlias;
      if (!settings.getKeyAlias().isPresent()) {
        if (ks.aliases().hasMoreElements()) {
          String inferredAlias = ks.aliases().nextElement();
          logger.info("SSL ssl.key_alias not configured, took first alias in keystore: " + inferredAlias);
          sslKeyAlias = inferredAlias;
        }
        else {
          throw new SettingsMalformedException("No alias found, therefore key found in keystore!");
        }
      }
      else {
        sslKeyAlias = settings.getKeyAlias().get();
      }
      Key key = ks.getKey(sslKeyAlias, keyPassBa);
      if (key == null) {
        throw new SettingsMalformedException("Private key not found in keystore for alias: " + sslKeyAlias);
      }

      // Create a PEM of the private key
      StringBuilder sb = new StringBuilder();
      sb.append("---BEGIN PRIVATE KEY---\n");
      sb.append(Base64.getEncoder().encodeToString(key.getEncoded()));
      sb.append("\n");
      sb.append("---END PRIVATE KEY---");
      String privateKey = sb.toString();
      logger.info("Discovered key from JKS");

      // Get CertChain from keystore
      Certificate[] cchain = ks.getCertificateChain(sslKeyAlias);

      // Create a PEM of the certificate chain
      sb = new StringBuilder();
      for (Certificate c : cchain) {
        sb.append("-----BEGIN CERTIFICATE-----\n");
        sb.append(Base64.getEncoder().encodeToString(c.getEncoded()));
        sb.append("\n");
        sb.append("-----END CERTIFICATE-----\n");
      }
      String certChain = sb.toString();
      logger.info("Discovered cert chain from JKS");


      AccessController.doPrivileged(
        (PrivilegedAction<Void>) () -> {
          creator.mkSSLContext(certChain, privateKey);
          return null;
        });

    } catch (Throwable t) {
      logger.error("Failed to load SSL certs and keys from JKS Keystore!");
      if( t instanceof AccessControlException){
        logger.error("Check the JKS Keystore path is correct: " + settings.getKeystoreFile());
      }
      t.printStackTrace();
    }
  }

  public interface SSLContextCreator {
    void mkSSLContext(String certChain, String privateKey);
  }
}

