package edu.management;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class Accounts {
    enum Role {
        ADMIN, STUDENT, TEACHER
    }

    record Session(String login, Role role, String studentNo, String teacherNo) {
    }

    record Credential(Session session, byte[] salt, byte[] hash, int iterations) {
    }

    private static final int ITERATIONS = 600_000;
    private final LocalAccounts localAccounts;

    Accounts(Database database) {
        localAccounts = new LocalAccounts(database.localAccountsFile(), database);
    }

    void create(String login, Role role, String memberNo, char[] password) throws SQLException, IOException {
        if (login.isBlank() || !login.equals(login.strip()) || login.length() > 64) {
            throw new IllegalArgumentException("登录名长度应为 1 至 64 个字符，首尾不能有空格。");
        }
        if ((role == Role.ADMIN && memberNo != null)
                || (role != Role.ADMIN && (memberNo == null || memberNo.isBlank()))) {
            throw new IllegalArgumentException("学生和教师需要绑定学号或工号，教务账号不绑定业务编号。");
        }
        if (password.length < 10) {
            throw new IllegalArgumentException("密码至少需要 10 个字符。");
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS);
        try {
            localAccounts.create(login, role, memberNo, salt, hash, ITERATIONS);
        } finally {
            Arrays.fill(hash, (byte) 0);
        }
    }

    Session authenticate(String login, char[] password) throws SQLException, IOException {
        Credential stored = localAccounts.find(login);
        if (stored == null
                || !MessageDigest.isEqual(stored.hash(), derive(password, stored.salt(), stored.iterations()))) {
            throw new IllegalArgumentException("登录名或密码错误。");
        }
        Session session = stored.session();
        if (session.role() != Role.ADMIN) {
            String memberNo = session.role() == Role.STUDENT ? session.studentNo() : session.teacherNo();
            if (localAccounts.findMember(session.role(), memberNo) == null) {
                throw new IllegalArgumentException("账号绑定的学生或教师记录已不存在。");
            }
        }
        return session;
    }

    boolean deleteStudent(StudentDao students, String studentNo) throws SQLException, IOException {
        return localAccounts.deleteStudent(students, studentNo);
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK 无法使用 PBKDF2WithHmacSHA256。", e);
        } finally {
            spec.clearPassword();
        }
    }
}
