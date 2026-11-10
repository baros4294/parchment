import java.io.File;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.Arrays;

/** Check actual key decryption before launching the Android build. No credentials are logged. */
class ValidateAndroidSigningKey {
    public static void main(String[] args) {
        char[] storePassword = new char[0];
        char[] keyPassword = new char[0];
        int exitCode = 0;
        try {
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD").toCharArray();
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD").toCharArray();
            KeyStore store = KeyStore.getInstance(new File(System.getenv("ANDROID_KEYSTORE_PATH")), storePassword);
            if (!(store.getKey(System.getenv("ANDROID_KEY_ALIAS"), keyPassword) instanceof PrivateKey)) {
                throw new IllegalStateException("Signer must contain a readable private key");
            }
        } catch (Exception error) {
            System.err.println("Private release key could not be decrypted; check the key password and alias");
            exitCode = 1;
        } finally {
            Arrays.fill(storePassword, (char) 0);
            Arrays.fill(keyPassword, (char) 0);
        }
        if (exitCode != 0) System.exit(exitCode);
    }
}
