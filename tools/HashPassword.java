import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCrypt;
class HashPassword {
    public static void main(String[] args) throws Exception {
        String value=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine();
        if(value==null || value.length()<12) throw new IllegalArgumentException("At least 12 characters required.");
        System.out.print(BCrypt.hashpw(value,BCrypt.gensalt(12)));
    }
}
