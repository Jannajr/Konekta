import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class Konekta {

    // ------------------------------------------------------------------ configuração
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    static final boolean PRODUCAO = Boolean.parseBoolean(System.getenv().getOrDefault("KONEKTA_PRODUCAO", "false"));
    static final String GESTAO_PIN = System.getenv().getOrDefault("KONEKTA_PIN", PRODUCAO ? "" : "2026");
    static final boolean DEMO = Boolean.parseBoolean(System.getenv().getOrDefault("KONEKTA_DEMO", PRODUCAO ? "false" : "true"));
    static final String DB_FILE = System.getenv().getOrDefault("KONEKTA_DB", "konekta.db");
    static final String ARQ_FILE = System.getenv().getOrDefault("KONEKTA_ARQ", "konekta-dados.txt");
    static final double TAXA = 0.15; // comissão de intermediação (15%)
    static final long RETENCAO_MS = (long) (Double.parseDouble(
            System.getenv().getOrDefault("KONEKTA_RETENCAO_HORAS", "24")) * 3600_000L);
    static final long SESSAO_MS = 30L * 24 * 3600_000L;
    static final double CENTRO_LAT = -15.1165, CENTRO_LNG = 39.2666; // Nampula
    static final String TEL_SUPORTE = tel("KONEKTA_TEL_SUPORTE", "+258 84 000 0000");
    static final String TEL_EMERGENCIA = tel("KONEKTA_TEL_EMERGENCIA", "+258 84 000 0001");
    static final String TEL_PAGAMENTOS = tel("KONEKTA_TEL_PAGAMENTOS", "+258 84 000 0002");

    static final SecureRandom RND = new SecureRandom();
    static final Object LOCK = new Object();
    static final Map<String, Cliente> CLIENTES = new LinkedHashMap<>();
    static final Map<String, Pedido> PEDIDOS = new LinkedHashMap<>();
    static final Map<String, Prestador> PRESTADORES = new LinkedHashMap<>();
    static final Map<String, Sessao> SESSOES = new HashMap<>();
    static final Map<String, Long> SESSOES_GESTAO = new HashMap<>();
    static final Map<String, long[]> TENTATIVAS = new HashMap<>(); // [falhas, bloqueadoAte]
    static final ThreadLocal<String> IP = new ThreadLocal<>();

    static String tel(String chave, String def) {
        return System.getenv().getOrDefault(chave, def).replaceAll("[^0-9+ ]", "").trim();
    }

    // ------------------------------------------------------------------ modelo
    static class Cliente {
        String id, nome, telefone, salt, hash;
        long criadoEm;
    }

    static class Sessao {
        String clienteId;
        long expira;
    }

    static class Prestador {
        String id, salt, pinHash, senhaSalt = "", senhaHash = "", nome, telefone, categoria, bairro;
        boolean aprovado, selo;
        double ganhos;
        int servicos, somaNotas, nNotas;
    }

    static class Pedido {
        String codigo, clienteId = "", modulo, categoria, descricao, bairro, endereco, nome, telefone;
        String estado = "RECEBIDO";      // RECEBIDO, ORCAMENTO, APROVADO, EM_CURSO, CONCLUIDO, CANCELADO
        String pagamento = "NENHUM";     // NENHUM, PARCIAL (50%), RETIDO (100%), LIBERADO, REEMBOLSADO
        String metodo = "", metodo2 = "", prestadorId = "", comentario = "";
        double valor, pago, lat, lng, pLat, pLng;
        long criadoEm, concluidoEm, pago2Em, pEm;
        int notaPrestador, notaCliente;  // avaliação dupla
    }

    static class ApiErro extends RuntimeException {
        final int code;
        ApiErro(int code, String msg) { super(msg); this.code = code; }
    }

    // ------------------------------------------------------------------ arranque
    public static void main(String[] args) throws Exception {
        if (PRODUCAO && (GESTAO_PIN.length() < 16 || GESTAO_PIN.equals("2026"))) {
            throw new IllegalStateException("PUBLICAÇÃO BLOQUEADA: defina KONEKTA_PIN com pelo menos 16 caracteres aleatórios.");
        }
        if (PRODUCAO && DEMO) {
            throw new IllegalStateException("PUBLICAÇÃO BLOQUEADA: KONEKTA_DEMO deve ser false.");
        }
        abrirBd();
        synchronized (LOCK) {
            if (DEMO && PRESTADORES.isEmpty()) semear();
            sincronizar();
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Konekta::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        ScheduledExecutorService sch = Executors.newSingleThreadScheduledExecutor();
        sch.scheduleAtFixedRate(() -> {
            try { tick(); } catch (Exception e) { e.printStackTrace(); }
        }, 5, 5, TimeUnit.SECONDS);

        System.out.println("==================================================");
        System.out.println(" KONEKTA a correr em http://localhost:" + PORT);
        System.out.println(" Armazenamento    : " + BD_MODO + (BD_MOTIVO.isEmpty() ? "" : "  (" + BD_MOTIVO + ")"));
        System.out.println(" Centro de Gestao : #/gestao   (PIN: " + GESTAO_PIN + ")");
        if (GESTAO_PIN.equals("2026"))
            System.out.println(" AVISO: PIN por defeito. Mude com a variavel KONEKTA_PIN antes de publicar.");
        if (DEMO) System.out.println(" Prestadores demo : PRS-0001/1111  PRS-0002/2222  PRS-0003/3333");
        System.out.println(" Suporte " + TEL_SUPORTE + " | Emergencia " + TEL_EMERGENCIA + " | Pagamentos " + TEL_PAGAMENTOS);
        System.out.println("==================================================");
    }

    static void semear() {
        novoPrestador("PRS-0001", "1111", "Carlos Mussa", "841000001", "Eletricidade", "Muatala", true, true);
        novoPrestador("PRS-0002", "2222", "Amina Selemane", "841000002", "Informática", "Napipine", true, false);
        novoPrestador("PRS-0003", "3333", "Joaquim Rassul", "841000003", "Carrinha", "Carrupeia", true, true);
    }

    static void novoPrestador(String id, String pin, String nome, String tel, String cat, String bairro,
                              boolean aprovado, boolean selo) {
        Prestador p = new Prestador();
        p.id = id; p.nome = nome; p.telefone = tel; p.categoria = cat; p.bairro = bairro;
        p.aprovado = aprovado; p.selo = selo;
        p.salt = novoSal();
        p.pinHash = hash(pin, p.salt);
        PRESTADORES.put(id, p);
    }

    /** Tarefa periódica: liberta pagamentos, simula o deslocamento, limpa sessões e grava alterações. */
    static void tick() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            for (Pedido p : PEDIDOS.values()) {
                if (p.estado.equals("CONCLUIDO") && p.pagamento.equals("RETIDO") && p.pago2Em > 0
                        && now - p.pago2Em >= RETENCAO_MS) {
                    liberar(p);
                }
                if (p.estado.equals("EM_CURSO") && now - p.pEm > 15000) { // sem GPS real: simula
                    p.pLat += (p.lat - p.pLat) * 0.06;
                    p.pLng += (p.lng - p.pLng) * 0.06;
                }
            }
            SESSOES.values().removeIf(s -> s.expira < now);
            SESSOES_GESTAO.values().removeIf(expira -> expira < now);
            sincronizar();
        }
    }

    static void liberar(Pedido p) {
        p.pagamento = "LIBERADO";
        Prestador pr = PRESTADORES.get(p.prestadorId);
        if (pr != null) { pr.ganhos += p.valor * (1 - TAXA); pr.servicos++; }
    }

    // ------------------------------------------------------------------ segurança
    static String novoSal() {
        byte[] b = new byte[16];
        RND.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String hash(String segredo, String sal) {
        try {
            PBEKeySpec spec = new PBEKeySpec(segredo.toCharArray(), HexFormat.of().parseHex(sal), 60000, 256);
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean confere(String segredo, String sal, String h) {
        return MessageDigest.isEqual(hash(segredo, sal).getBytes(StandardCharsets.UTF_8), h.getBytes(StandardCharsets.UTF_8));
    }

    static String aleatorio(int n) {
        String a = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(a.charAt(RND.nextInt(a.length())));
        return sb.toString();
    }

    static String chave(String k) { return k + "@" + IP.get(); }

    static void verificarBloqueio(String k) {
        long[] t = TENTATIVAS.get(chave(k));
        if (t != null && t[1] > System.currentTimeMillis())
            throw new ApiErro(429, "Demasiadas tentativas erradas. Aguarde 5 minutos e tente de novo.");
    }

    static void falhou(String k) {
        long[] t = TENTATIVAS.computeIfAbsent(chave(k), x -> new long[2]);
        if (++t[0] >= 5) { t[0] = 0; t[1] = System.currentTimeMillis() + 300_000; }
    }

    static void sucesso(String k) { TENTATIVAS.remove(chave(k)); }

    static String novaSessao(Cliente c) {
        byte[] b = new byte[24];
        RND.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        Sessao s = new Sessao();
        s.clienteId = c.id;
        s.expira = System.currentTimeMillis() + SESSAO_MS;
        SESSOES.put(token, s);
        return token;
    }

    static Cliente clienteAuth(Map<String, String> f) {
        String token = txt(f, "token", 100);
        Sessao s = SESSOES.get(token);
        if (s == null || s.expira < System.currentTimeMillis()) {
            SESSOES.remove(token);
            throw new ApiErro(401, "Sessão expirada. Entre novamente.");
        }
        Cliente c = CLIENTES.get(s.clienteId);
        if (c == null) throw new ApiErro(401, "Sessão expirada. Entre novamente.");
        return c;
    }

    // ================================================================== ARMAZENAMENTO
    static Connection DB;
    static String BD_MODO = "memoria", BD_MOTIVO = "";
    static final Map<String, String> SIG = new HashMap<>(); // assinaturas do que já está gravado

    static final String CLI_COLS = "id,nome,telefone,salt,hash,criado_em";
    static final String PRS_COLS = "id,salt,pin_hash,nome,telefone,categoria,bairro,aprovado,selo,ganhos,servicos,soma_notas,n_notas,senha_salt,senha_hash";
    static final String PED_COLS = "codigo,cliente_id,modulo,categoria,descricao,bairro,endereco,nome,telefone,estado,pagamento,"
            + "metodo,prestador_id,comentario,valor,lat,lng,p_lat,p_lng,criado_em,concluido_em,p_em,nota_prestador,"
            + "nota_cliente,pago,pago2_em,metodo2";

    static Object[] valores(Cliente c) {
        return new Object[]{c.id, c.nome, c.telefone, c.salt, c.hash, c.criadoEm};
    }

    static Object[] valores(Prestador p) {
        return new Object[]{p.id, p.salt, p.pinHash, p.nome, p.telefone, p.categoria, p.bairro,
                p.aprovado ? 1 : 0, p.selo ? 1 : 0, p.ganhos, p.servicos, p.somaNotas, p.nNotas, p.senhaSalt, p.senhaHash};
    }

    static Object[] valores(Pedido p) {
        return new Object[]{p.codigo, p.clienteId, p.modulo, p.categoria, p.descricao, p.bairro, p.endereco,
                p.nome, p.telefone, p.estado, p.pagamento, p.metodo, p.prestadorId, p.comentario,
                p.valor, p.lat, p.lng, p.pLat, p.pLng, p.criadoEm, p.concluidoEm, p.pEm,
                p.notaPrestador, p.notaCliente, p.pago, p.pago2Em, p.metodo2};
    }

    static String junta(Object[] v) {
        StringBuilder sb = new StringBuilder();
        for (Object o : v) sb.append(o).append('|');
        return sb.toString();
    }

    static String sig(Cliente c) { return junta(valores(c)); }
    static String sig(Prestador p) { return junta(valores(p)); }

    static String sig(Pedido p) { // a posição do prestador (muda de segundos a segundos) não conta
        Object[] v = valores(p);
        v[17] = 0; v[18] = 0; v[21] = 0;
        return junta(v);
    }

    static String nz(String s) { return s == null ? "" : s; }

    static String qs(int n) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ",?");
        return sb.append(")").toString();
    }

    static String descreve(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String a = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String b = r == e ? "" : " [" + r.getClass().getSimpleName() + ": " + r.getMessage() + "]";
        String s = a + b;
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    static void abrirBd() {
        try {
            garantirDriver();
            abrirSqlite();
            BD_MODO = "sqlite";
            System.out.println(" Base de dados SQLite: " + new File(DB_FILE).getAbsolutePath());
            return;
        } catch (Throwable e) {
            BD_MOTIVO = descreve(e);
            System.out.println("*** SQLite indisponivel: " + BD_MOTIVO);
            try { if (DB != null) DB.close(); } catch (Exception x) { /* ignora */ }
            DB = null;
            CLIENTES.clear(); PEDIDOS.clear(); PRESTADORES.clear(); SIG.clear();
        }
        try {
            carregarFicheiro();
            BD_MODO = "ficheiro";
            System.out.println(" A guardar os dados no ficheiro: " + new File(ARQ_FILE).getAbsolutePath());
        } catch (Exception e) {
            BD_MODO = "memoria";
            BD_MOTIVO += " | ficheiro: " + descreve(e);
            System.out.println("*** Sem armazenamento: os dados perdem-se ao parar. " + descreve(e));
        }
    }

    // ---- driver SQLite: procura, descarrega (se preciso), valida e carrega
    static final String JAR_NAME = "sqlite-jdbc-3.41.2.2.jar";
    static final String[] JAR_URLS = {
        "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.41.2.2/sqlite-jdbc-3.41.2.2.jar",
        "https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.41.2.2/sqlite-jdbc-3.41.2.2.jar"
    };

    static boolean jarValido(File f) {
        if (!f.isFile() || f.length() < 1_000_000) return false;
        try (InputStream in = new FileInputStream(f)) {
            return in.read() == 'P' && in.read() == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    static File procurarJar() {
        File[] lista = new File(".").listFiles((d, n) -> n.startsWith("sqlite-jdbc-") && n.endsWith(".jar"));
        if (lista == null) return null;
        for (File f : lista) {
            if (jarValido(f)) return f;
            System.out.println(" Ficheiro " + f.getName() + " esta incompleto/corrompido. A apagar para descarregar de novo.");
            f.delete();
        }
        return null;
    }

    static File descarregarJar() throws IOException {
        Exception ultimo = null;
        HttpClient cliente = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        for (String url : JAR_URLS) {
            File parte = new File(JAR_NAME + ".part");
            try {
                System.out.println(" A descarregar o driver SQLite: " + url);
                parte.delete();
                HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(240)).GET().build();
                HttpResponse<Path> rs = cliente.send(rq, HttpResponse.BodyHandlers.ofFile(parte.toPath()));
                if (rs.statusCode() != 200) throw new IOException("HTTP " + rs.statusCode());
                if (!jarValido(parte)) throw new IOException("o ficheiro descarregado esta incompleto");
                File jar = new File(JAR_NAME);
                Files.move(parte.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                System.out.println(" Driver descarregado: " + jar.getAbsolutePath());
                return jar;
            } catch (Exception e) {
                ultimo = e;
                parte.delete();
                System.out.println(" Falhou: " + descreve(e));
            }
        }
        throw new IOException("nao foi possivel descarregar o driver (" + descreve(ultimo)
                + "). Descarregue-o manualmente de " + JAR_URLS[0] + " e ponha-o ao lado do Konekta.java");
    }

    static void garantirDriver() throws Exception {
        try { // caso o programa tenha sido corrido com -cp sqlite-jdbc.jar
            Class.forName("org.sqlite.JDBC");
            return;
        } catch (ClassNotFoundException e) { /* continua: procura o jar */ }
        File jar = procurarJar();
        if (jar == null) jar = descarregarJar();
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, Konekta.class.getClassLoader());
        Class<?> cls = Class.forName("org.sqlite.JDBC", true, loader);
        Driver d = (Driver) cls.getDeclaredConstructor().newInstance();
        DriverManager.registerDriver(new DriverShim(d)); // o DriverManager só aceita drivers do mesmo classloader
        System.out.println(" Driver SQLite carregado: " + jar.getName());
    }

    static class DriverShim implements Driver {
        private final Driver d;
        DriverShim(Driver d) { this.d = d; }
        public Connection connect(String url, Properties info) throws SQLException { return d.connect(url, info); }
        public boolean acceptsURL(String url) throws SQLException { return d.acceptsURL(url); }
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException { return d.getPropertyInfo(url, info); }
        public int getMajorVersion() { return d.getMajorVersion(); }
        public int getMinorVersion() { return d.getMinorVersion(); }
        public boolean jdbcCompliant() { return d.jdbcCompliant(); }
        public Logger getParentLogger() throws SQLFeatureNotSupportedException { return d.getParentLogger(); }
    }

    // ---- SQLite
    static void abrirSqlite() throws SQLException {
        DB = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
        try (Statement s = DB.createStatement()) {
            try { s.execute("PRAGMA journal_mode=WAL"); } catch (SQLException e) { /* opcional */ }
            s.execute("CREATE TABLE IF NOT EXISTS clientes(id TEXT PRIMARY KEY, nome TEXT NOT NULL, "
                    + "telefone TEXT NOT NULL UNIQUE, salt TEXT NOT NULL, hash TEXT NOT NULL, criado_em INTEGER)");
            s.execute("CREATE TABLE IF NOT EXISTS prestadores(id TEXT PRIMARY KEY, salt TEXT, pin_hash TEXT, "
                    + "nome TEXT, telefone TEXT, categoria TEXT, bairro TEXT, aprovado INTEGER, selo INTEGER, "
                    + "ganhos REAL, servicos INTEGER, soma_notas INTEGER, n_notas INTEGER, "
                    + "senha_salt TEXT DEFAULT '', senha_hash TEXT DEFAULT '')");
            for (String col : new String[]{"senha_salt TEXT DEFAULT ''", "senha_hash TEXT DEFAULT ''"}) {
                try { s.execute("ALTER TABLE prestadores ADD COLUMN " + col); } catch (SQLException e) { /* já existe */ }
            }
            s.execute("CREATE TABLE IF NOT EXISTS pedidos(codigo TEXT PRIMARY KEY, cliente_id TEXT, modulo TEXT, "
                    + "categoria TEXT, descricao TEXT, bairro TEXT, endereco TEXT, nome TEXT, telefone TEXT, "
                    + "estado TEXT, pagamento TEXT, metodo TEXT, prestador_id TEXT, comentario TEXT, valor REAL, "
                    + "lat REAL, lng REAL, p_lat REAL, p_lng REAL, criado_em INTEGER, concluido_em INTEGER, "
                    + "p_em INTEGER, nota_prestador INTEGER, nota_cliente INTEGER, "
                    + "pago REAL DEFAULT 0, pago2_em INTEGER DEFAULT 0, metodo2 TEXT DEFAULT '')");
            // bases criadas por versões anteriores: acrescenta as colunas do pagamento 50%+50%
            for (String col : new String[]{"pago REAL DEFAULT 0", "pago2_em INTEGER DEFAULT 0", "metodo2 TEXT DEFAULT ''"}) {
                try { s.execute("ALTER TABLE pedidos ADD COLUMN " + col); } catch (SQLException e) { /* já existe */ }
            }
        }
        carregarSqlite();
    }

    static void carregarSqlite() throws SQLException {
        try (Statement s = DB.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT * FROM clientes")) {
                while (r.next()) {
                    Cliente c = new Cliente();
                    c.id = r.getString("id"); c.nome = r.getString("nome"); c.telefone = r.getString("telefone");
                    c.salt = r.getString("salt"); c.hash = r.getString("hash"); c.criadoEm = r.getLong("criado_em");
                    CLIENTES.put(c.id, c);
                    SIG.put("c:" + c.id, sig(c));
                }
            }
            try (ResultSet r = s.executeQuery("SELECT * FROM prestadores")) {
                while (r.next()) {
                    Prestador p = new Prestador();
                    p.id = r.getString("id"); p.salt = r.getString("salt"); p.pinHash = r.getString("pin_hash");
                    p.senhaSalt = nz(r.getString("senha_salt")); p.senhaHash = nz(r.getString("senha_hash"));
                    p.nome = r.getString("nome"); p.telefone = r.getString("telefone");
                    p.categoria = r.getString("categoria"); p.bairro = r.getString("bairro");
                    p.aprovado = r.getInt("aprovado") == 1; p.selo = r.getInt("selo") == 1;
                    p.ganhos = r.getDouble("ganhos"); p.servicos = r.getInt("servicos");
                    p.somaNotas = r.getInt("soma_notas"); p.nNotas = r.getInt("n_notas");
                    PRESTADORES.put(p.id, p);
                    SIG.put("r:" + p.id, sig(p));
                }
            }
            try (ResultSet r = s.executeQuery("SELECT * FROM pedidos ORDER BY criado_em")) {
                while (r.next()) {
                    Pedido p = new Pedido();
                    p.codigo = r.getString("codigo"); p.clienteId = nz(r.getString("cliente_id"));
                    p.modulo = r.getString("modulo"); p.categoria = r.getString("categoria");
                    p.descricao = r.getString("descricao"); p.bairro = r.getString("bairro");
                    p.endereco = nz(r.getString("endereco")); p.nome = r.getString("nome");
                    p.telefone = r.getString("telefone"); p.estado = r.getString("estado");
                    p.pagamento = r.getString("pagamento"); p.metodo = nz(r.getString("metodo"));
                    p.prestadorId = nz(r.getString("prestador_id")); p.comentario = nz(r.getString("comentario"));
                    p.valor = r.getDouble("valor"); p.lat = r.getDouble("lat"); p.lng = r.getDouble("lng");
                    p.pLat = r.getDouble("p_lat"); p.pLng = r.getDouble("p_lng");
                    p.criadoEm = r.getLong("criado_em"); p.concluidoEm = r.getLong("concluido_em");
                    p.pEm = r.getLong("p_em"); p.notaPrestador = r.getInt("nota_prestador");
                    p.notaCliente = r.getInt("nota_cliente");
                    p.pago = r.getDouble("pago"); p.pago2Em = r.getLong("pago2_em");
                    p.metodo2 = nz(r.getString("metodo2"));
                    PEDIDOS.put(p.codigo, p);
                    SIG.put("p:" + p.codigo, sig(p));
                }
            }
        }
    }

    static void gravar(String sql, Object[] a) throws SQLException {
        try (PreparedStatement ps = DB.prepareStatement(sql)) {
            for (int i = 0; i < a.length; i++) ps.setObject(i + 1, a[i]);
            ps.executeUpdate();
        }
    }

    /** Devolve o que mudou desde a última gravação (chave -> assinatura nova). */
    static Map<String, String> mudancas() {
        Map<String, String> n = new HashMap<>();
        for (Cliente c : CLIENTES.values()) {
            String s = sig(c);
            if (!s.equals(SIG.get("c:" + c.id))) n.put("c:" + c.id, s);
        }
        for (Prestador p : PRESTADORES.values()) {
            String s = sig(p);
            if (!s.equals(SIG.get("r:" + p.id))) n.put("r:" + p.id, s);
        }
        for (Pedido p : PEDIDOS.values()) {
            String s = sig(p);
            if (!s.equals(SIG.get("p:" + p.codigo))) n.put("p:" + p.codigo, s);
        }
        return n;
    }

    /** Grava o que mudou (chamado sempre dentro do LOCK). */
    static void sincronizar() {
        if (DB == null && !BD_MODO.equals("ficheiro")) return;
        Map<String, String> novas = mudancas();
        if (novas.isEmpty()) return;
        if (DB == null) { // modo ficheiro
            try {
                escreverFicheiro();
                SIG.putAll(novas);
            } catch (IOException e) {
                System.err.println("Erro ao gravar o ficheiro de dados: " + descreve(e));
            }
            return;
        }
        try {
            DB.setAutoCommit(false);
            for (Cliente c : CLIENTES.values())
                if (novas.containsKey("c:" + c.id))
                    gravar("INSERT OR REPLACE INTO clientes(" + CLI_COLS + ") VALUES" + qs(6), valores(c));
            for (Prestador p : PRESTADORES.values())
                if (novas.containsKey("r:" + p.id))
                    gravar("INSERT OR REPLACE INTO prestadores(" + PRS_COLS + ") VALUES" + qs(15), valores(p));
            for (Pedido p : PEDIDOS.values())
                if (novas.containsKey("p:" + p.codigo))
                    gravar("INSERT OR REPLACE INTO pedidos(" + PED_COLS + ") VALUES" + qs(27), valores(p));
            DB.commit();
            SIG.putAll(novas);
        } catch (SQLException e) {
            try { DB.rollback(); } catch (SQLException x) { /* ignora */ }
            System.err.println("Erro ao gravar na base de dados: " + descreve(e));
        } finally {
            try { DB.setAutoCommit(true); } catch (SQLException x) { /* ignora */ }
        }
    }

    // ---- plano B: ficheiro de texto (não precisa de nenhuma biblioteca)
    static String enc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    static String dec(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    static void linha(StringBuilder sb, String tipo, Object[] v) {
        sb.append(tipo);
        for (Object o : v) sb.append('\t').append(enc(String.valueOf(o)));
        sb.append('\n');
    }

    static void escreverFicheiro() throws IOException {
        StringBuilder sb = new StringBuilder("# KONEKTA dados\n");
        for (Cliente c : CLIENTES.values()) linha(sb, "C", valores(c));
        for (Prestador p : PRESTADORES.values()) linha(sb, "R", valores(p));
        for (Pedido p : PEDIDOS.values()) linha(sb, "P", valores(p));
        Path tmp = Path.of(ARQ_FILE + ".tmp"), dest = Path.of(ARQ_FILE);
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void carregarFicheiro() throws IOException {
        Path f = Path.of(ARQ_FILE);
        if (!Files.exists(f)) return;
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (l.isEmpty() || l.startsWith("#")) continue;
            try {
                String[] a = l.split("\t", -1);
                for (int i = 1; i < a.length; i++) a[i] = dec(a[i]);
                String[] v = Arrays.copyOfRange(a, 1, a.length);
                switch (a[0]) {
                    case "C" -> { Cliente c = clienteDe(v); CLIENTES.put(c.id, c); SIG.put("c:" + c.id, sig(c)); }
                    case "R" -> { Prestador p = prestadorDe(v); PRESTADORES.put(p.id, p); SIG.put("r:" + p.id, sig(p)); }
                    case "P" -> { Pedido p = pedidoDe(v); PEDIDOS.put(p.codigo, p); SIG.put("p:" + p.codigo, sig(p)); }
                    default -> { }
                }
            } catch (RuntimeException e) {
                System.err.println("Linha ignorada no ficheiro de dados: " + descreve(e));
            }
        }
    }

    static Cliente clienteDe(String[] a) {
        Cliente c = new Cliente();
        c.id = a[0]; c.nome = a[1]; c.telefone = a[2]; c.salt = a[3]; c.hash = a[4];
        c.criadoEm = Long.parseLong(a[5]);
        return c;
    }

    static Prestador prestadorDe(String[] a) {
        Prestador p = new Prestador();
        p.id = a[0]; p.salt = a[1]; p.pinHash = a[2]; p.nome = a[3]; p.telefone = a[4];
        p.categoria = a[5]; p.bairro = a[6]; p.aprovado = a[7].equals("1"); p.selo = a[8].equals("1");
        p.ganhos = Double.parseDouble(a[9]); p.servicos = Integer.parseInt(a[10]);
        p.somaNotas = Integer.parseInt(a[11]); p.nNotas = Integer.parseInt(a[12]);
        if (a.length > 13) p.senhaSalt = a[13];
        if (a.length > 14) p.senhaHash = a[14];
        return p;
    }

    static Pedido pedidoDe(String[] a) {
        Pedido p = new Pedido();
        p.codigo = a[0]; p.clienteId = a[1]; p.modulo = a[2]; p.categoria = a[3]; p.descricao = a[4];
        p.bairro = a[5]; p.endereco = a[6]; p.nome = a[7]; p.telefone = a[8]; p.estado = a[9];
        p.pagamento = a[10]; p.metodo = a[11]; p.prestadorId = a[12]; p.comentario = a[13];
        p.valor = Double.parseDouble(a[14]); p.lat = Double.parseDouble(a[15]); p.lng = Double.parseDouble(a[16]);
        p.pLat = Double.parseDouble(a[17]); p.pLng = Double.parseDouble(a[18]);
        p.criadoEm = Long.parseLong(a[19]); p.concluidoEm = Long.parseLong(a[20]); p.pEm = Long.parseLong(a[21]);
        p.notaPrestador = Integer.parseInt(a[22]); p.notaCliente = Integer.parseInt(a[23]);
        p.pago = Double.parseDouble(a[24]); p.pago2Em = Long.parseLong(a[25]); p.metodo2 = a[26];
        return p;
    }

    // ------------------------------------------------------------------ HTTP
    static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (method.equals("GET")) {
                if (path.equals("/favicon.ico")) { ex.sendResponseHeaders(204, -1); ex.close(); return; }
                if (path.equals("/css/style.css")) {
                    send(ex, 200, "text/css", carregarArquivo("css/style.css"));
                    return;
                }
                if (path.equals("/js/script.js")) {
                    send(ex, 200, "application/javascript", carregarArquivo("js/script.js"));
                    return;
                }
                if (path.equals("/pages/trabalhadores.png")) {
                    byte[] imagem = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("pages", "trabalhadores.png"));
                    ex.getResponseHeaders().set("Content-Type", "image/png");
                    ex.sendResponseHeaders(200, imagem.length);
                    try (var out = ex.getResponseBody()) { out.write(imagem); }
                    return;
                }
                send(ex, 200, "text/html", paginaApp()); // página principal; estilos e scripts estão em ficheiros separados
                return;
            }
            if (method.equals("POST") && path.startsWith("/api/")) {
                Map<String, String> f = form(ex);
                Object r;
                synchronized (LOCK) {
                    IP.set(ex.getRemoteAddress().getAddress().getHostAddress());
                    r = api(path, f);
                    sincronizar();
                }
                send(ex, 200, "application/json", json(r));
                return;
            }
            send(ex, 405, "application/json", json(m("erro", "Método não permitido")));
        } catch (ApiErro e) {
            send(ex, e.code, "application/json", json(m("erro", e.getMessage())));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json", json(m("erro", "Erro interno do servidor")));
        }
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        ex.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=(self)");
        if (PRODUCAO) ex.getResponseHeaders().set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static Map<String, String> form(HttpExchange ex) throws IOException {
        String s = new String(ex.getRequestBody().readNBytes(30000), StandardCharsets.UTF_8);
        Map<String, String> r = new HashMap<>();
        if (s.isEmpty()) return r;
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            r.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                  URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return r;
    }

    // ------------------------------------------------------------------ JSON mínimo
    static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    static String json(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return "\"" + esc(s) + "\"";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Map<?, ?> mp) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : mp.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(json(String.valueOf(e.getKey()))).append(':').append(json(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof Collection<?> c) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(',');
                first = false;
                sb.append(json(x));
            }
            return sb.append(']').toString();
        }
        return json(o.toString());
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> { }
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ utilitários
    static String cod(String prefixo, int n) {
        String a = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(prefixo).append('-');
        for (int i = 0; i < n; i++) sb.append(a.charAt(RND.nextInt(a.length())));
        return sb.toString();
    }

    static String digitos(String s) { return s == null ? "" : s.replaceAll("[^0-9]", ""); }

    static String txt(Map<String, String> f, String k, int max) {
        String v = f.getOrDefault(k, "").trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    static double num(Map<String, String> f, String k, double def) {
        try { return Double.parseDouble(f.getOrDefault(k, "")); } catch (Exception e) { return def; }
    }

    static int nota(Map<String, String> f) {
        int n = (int) num(f, "nota", 0);
        if (n < 1 || n > 5) throw new ApiErro(400, "A nota deve ser de 1 a 5.");
        return n;
    }

    static String primeiroNome(String n) { int i = n.indexOf(' '); return i < 0 ? n : n.substring(0, i); }

    static double media(int soma, int n) { return n == 0 ? 0 : Math.round(10.0 * soma / n) / 10.0; }

    static double entrada(Pedido p) { return Math.round(p.valor / 2.0); } // 50% no início
    static double restante(Pedido p) { return p.valor - entrada(p); }     // 50% no fim

    static String metodoValido(Map<String, String> f) {
        String metodo = txt(f, "metodo", 20);
        if (!List.of("M-Pesa", "e-Mola", "mKesh").contains(metodo)) throw new ApiErro(400, "Escolha o método de pagamento.");
        return metodo;
    }

    // ------------------------------------------------------------------ API
    static Object api(String path, Map<String, String> f) {
        return switch (path) {
            case "/api/cliente/registar" -> registarCliente(f);
            case "/api/cliente/entrar" -> entrarCliente(f);
            case "/api/cliente/eu" -> { Cliente c = clienteAuth(f); yield m("nome", c.nome, "telefone", c.telefone); }
            case "/api/cliente/sair" -> sairCliente(f);
            case "/api/cliente/senha" -> mudarSenha(f);
            case "/api/cliente/pedidos" -> meusPedidos(f);
            case "/api/pedidos" -> criarPedido(f);
            case "/api/acompanhar" -> acompanhar(f);
            case "/api/aceitar" -> aceitar(f);
            case "/api/pagar-final" -> pagarFinal(f);
            case "/api/avaliar" -> avaliar(f);
            case "/api/cancelar" -> cancelar(f);
            case "/api/prestadores" -> registarPrestador(f);
            case "/api/prestador/painel" -> painelPrestador(f);
            case "/api/prestador/acao" -> acaoPrestador(f);
            case "/api/prestador/posicao" -> posicaoPrestador(f);
            case "/api/prestador/avaliar" -> avaliarCliente(f);
            case "/api/gestao/entrar" -> entrarGestao(f);
            case "/api/gestao/sair" -> sairGestao(f);
            case "/api/gestao/painel" -> painelGestao(f);
            case "/api/gestao/orcamento" -> orcamento(f);
            case "/api/gestao/prestador" -> gerirPrestador(f);
            case "/api/gestao/prestador-pin" -> novoPinPrestador(f);
            case "/api/gestao/repor-senha" -> reporSenha(f);
            case "/api/gestao/confirmar-final" -> confirmarFinal(f);
            case "/api/gestao/liberar" -> liberarManual(f);
            case "/api/gestao/cancelar" -> cancelarGestao(f);
            default -> throw new ApiErro(404, "Rota inexistente");
        };
    }

    // ---- conta do cliente
    static Object registarCliente(Map<String, String> f) {
        String nome = txt(f, "nome", 60), tel = digitos(txt(f, "telefone", 20));
        String senha = f.getOrDefault("senha", "");
        if (nome.isEmpty()) throw new ApiErro(400, "Indique o seu nome.");
        if (tel.length() < 9) throw new ApiErro(400, "Indique um telefone válido (9 dígitos).");
        if (senha.length() < 6 || senha.length() > 72) throw new ApiErro(400, "A senha deve ter pelo menos 6 caracteres.");
        for (Cliente x : CLIENTES.values())
            if (x.telefone.equals(tel)) throw new ApiErro(409, "Este telefone já tem conta. Use Entrar.");
        Cliente c = new Cliente();
        do { c.id = cod("CLI", 5); } while (CLIENTES.containsKey(c.id));
        c.nome = nome; c.telefone = tel; c.criadoEm = System.currentTimeMillis();
        c.salt = novoSal();
        c.hash = hash(senha, c.salt);
        CLIENTES.put(c.id, c);
        return m("token", novaSessao(c), "nome", c.nome, "telefone", c.telefone);
    }

    static Object entrarCliente(Map<String, String> f) {
        String tel = digitos(txt(f, "telefone", 20));
        verificarBloqueio("cli:" + tel);
        Cliente c = null;
        for (Cliente x : CLIENTES.values()) if (x.telefone.equals(tel)) c = x;
        if (c == null || !confere(f.getOrDefault("senha", ""), c.salt, c.hash)) {
            falhou("cli:" + tel);
            throw new ApiErro(401, "Telefone ou senha incorretos.");
        }
        sucesso("cli:" + tel);
        return m("token", novaSessao(c), "nome", c.nome, "telefone", c.telefone);
    }

    static Object sairCliente(Map<String, String> f) {
        SESSOES.remove(txt(f, "token", 100));
        return m("ok", true);
    }

    static Object mudarSenha(Map<String, String> f) {
        Cliente c = clienteAuth(f);
        String nova = f.getOrDefault("nova", "");
        verificarBloqueio("cli:" + c.telefone);
        if (!confere(f.getOrDefault("atual", ""), c.salt, c.hash)) {
            falhou("cli:" + c.telefone);
            throw new ApiErro(401, "A senha atual está incorreta.");
        }
        if (nova.length() < 6 || nova.length() > 72) throw new ApiErro(400, "A nova senha deve ter pelo menos 6 caracteres.");
        c.salt = novoSal();
        c.hash = hash(nova, c.salt);
        return m("ok", true);
    }

    static Object meusPedidos(Map<String, String> f) {
        Cliente c = clienteAuth(f);
        List<Object> lista = new ArrayList<>();
        for (Pedido p : PEDIDOS.values()) {
            if (!p.clienteId.equals(c.id)) continue;
            lista.add(0, m("codigo", p.codigo, "modulo", p.modulo, "categoria", p.categoria, "estado", p.estado,
                    "pagamento", p.pagamento, "valor", p.valor, "criadoEm", p.criadoEm));
        }
        return m("pedidos", lista);
    }

    // ---- pedidos do cliente
    static Object criarPedido(Map<String, String> f) {
        Cliente c = clienteAuth(f);
        int aguardam = 0;
        for (Pedido x : PEDIDOS.values())
            if (x.clienteId.equals(c.id) && (x.estado.equals("RECEBIDO") || x.estado.equals("ORCAMENTO"))) aguardam++;
        if (aguardam >= 5) throw new ApiErro(429, "Já tem 5 pedidos a aguardar. Espere pela gestão ou cancele algum.");
        Pedido p = new Pedido();
        p.modulo = txt(f, "modulo", 10);
        if (!List.of("TECNICA", "LOG", "SAUDE").contains(p.modulo)) throw new ApiErro(400, "Escolha um serviço válido.");
        p.categoria = txt(f, "categoria", 60);
        p.descricao = txt(f, "descricao", 500);
        p.bairro = txt(f, "bairro", 60);
        p.endereco = txt(f, "endereco", 120);
        if (p.categoria.isEmpty()) throw new ApiErro(400, "Escolha o tipo de serviço.");
        if (p.descricao.length() < 5) throw new ApiErro(400, "Descreva o pedido com um pouco mais de detalhe.");
        if (p.bairro.isEmpty()) throw new ApiErro(400, "Indique o bairro.");
        p.clienteId = c.id; p.nome = c.nome; p.telefone = c.telefone;
        p.lat = num(f, "lat", CENTRO_LAT + (RND.nextDouble() - 0.5) * 0.02);
        p.lng = num(f, "lng", CENTRO_LNG + (RND.nextDouble() - 0.5) * 0.02);
        p.criadoEm = System.currentTimeMillis();
        do { p.codigo = cod("KNK", 4); } while (PEDIDOS.containsKey(p.codigo));
        PEDIDOS.put(p.codigo, p);
        return m("codigo", p.codigo);
    }

    static Pedido pedidoDoCliente(Map<String, String> f) {
        Cliente c = clienteAuth(f);
        Pedido p = PEDIDOS.get(txt(f, "codigo", 20).toUpperCase());
        if (p == null || !p.clienteId.equals(c.id)) throw new ApiErro(404, "Pedido não encontrado na sua conta.");
        return p;
    }

    static Object acompanhar(Map<String, String> f) {
        Pedido p = pedidoDoCliente(f);
        Prestador pr = PRESTADORES.get(p.prestadorId);
        Map<String, Object> r = m(
                "codigo", p.codigo, "modulo", p.modulo, "categoria", p.categoria, "descricao", p.descricao,
                "bairro", p.bairro, "estado", p.estado, "pagamento", p.pagamento, "valor", p.valor,
                "entrada", entrada(p), "restante", restante(p), "pago", p.pago,
                "lat", p.lat, "lng", p.lng, "avaliado", p.notaPrestador > 0,
                "liberacaoEm", p.pago2Em > 0 ? p.pago2Em + RETENCAO_MS : 0,
                "agora", System.currentTimeMillis());
        // O cliente só vê o primeiro nome do prestador: nunca o contacto.
        r.put("prestador", pr == null || p.estado.equals("RECEBIDO") ? null : m(
                "nome", primeiroNome(pr.nome), "selo", pr.selo, "nota", media(pr.somaNotas, pr.nNotas)));
        r.put("posicao", p.estado.equals("EM_CURSO") ? m("lat", p.pLat, "lng", p.pLng) : null);
        return r;
    }

    /** Aprovar o orçamento = pagar os primeiros 50% (ficam retidos no KONEKTA Pay). */
    static Object aceitar(Map<String, String> f) {
        Pedido p = pedidoDoCliente(f);
        if (!p.estado.equals("ORCAMENTO")) throw new ApiErro(400, "Este pedido não tem orçamento para aprovar.");
        p.metodo = metodoValido(f);
        p.pago = entrada(p);
        p.pagamento = "PARCIAL";
        p.estado = "APROVADO";
        return m("ok", true, "pago", p.pago);
    }

    /** Os outros 50%: o cliente paga quando o prestador marca o serviço como concluído. */
    static Object pagarFinal(Map<String, String> f) {
        Pedido p = pedidoDoCliente(f);
        if (!p.estado.equals("CONCLUIDO") || !p.pagamento.equals("PARCIAL"))
            throw new ApiErro(400, "Não há pagamento final pendente neste pedido.");
        fecharPagamento(p, metodoValido(f));
        return m("ok", true);
    }

    static void fecharPagamento(Pedido p, String metodo) {
        p.metodo2 = metodo;
        p.pago = p.valor;
        p.pagamento = "RETIDO"; // 100% retido; libertado ao prestador 24h depois
        p.pago2Em = System.currentTimeMillis();
    }

    static Object avaliar(Map<String, String> f) {
        Pedido p = pedidoDoCliente(f);
        if (!p.estado.equals("CONCLUIDO")) throw new ApiErro(400, "Só pode avaliar depois do serviço concluído.");
        if (p.notaPrestador > 0) throw new ApiErro(400, "Este pedido já foi avaliado.");
        p.notaPrestador = nota(f);
        p.comentario = txt(f, "comentario", 300);
        Prestador pr = PRESTADORES.get(p.prestadorId);
        if (pr != null) { pr.somaNotas += p.notaPrestador; pr.nNotas++; }
        return m("ok", true);
    }

    static Object cancelar(Map<String, String> f) {
        Pedido p = pedidoDoCliente(f);
        if (!p.estado.equals("RECEBIDO") && !p.estado.equals("ORCAMENTO"))
            throw new ApiErro(400, "Já não é possível cancelar. Ligue para o suporte KONEKTA.");
        p.estado = "CANCELADO";
        return m("ok", true);
    }

    // ---- prestador
    static Object registarPrestador(Map<String, String> f) {
        Prestador p = new Prestador();
        p.nome = txt(f, "nome", 60);
        p.telefone = digitos(txt(f, "telefone", 20));
        p.categoria = txt(f, "categoria", 60);
        p.bairro = txt(f, "bairro", 60);
        String senha = f.getOrDefault("senha", "");
        if (p.nome.isEmpty() || p.categoria.isEmpty() || p.bairro.isEmpty() || p.telefone.length() < 9)
            throw new ApiErro(400, "Preencha todos os campos (telefone com pelo menos 9 dígitos).");
        if (senha.length() < 8 || senha.length() > 72)
            throw new ApiErro(400, "A senha deve ter entre 8 e 72 caracteres.");
        for (Prestador existente : PRESTADORES.values())
            if (existente.telefone.equals(p.telefone)) throw new ApiErro(409, "Este telefone já está registado como prestador.");
        do { p.id = cod("PRS", 4); } while (PRESTADORES.containsKey(p.id));
        p.salt = novoSal();
        p.pinHash = hash(String.format("%04d", RND.nextInt(10000)), p.salt); // compatibilidade com contas antigas
        p.senhaSalt = novoSal();
        p.senhaHash = hash(senha, p.senhaSalt);
        p.aprovado = false;
        synchronized (LOCK) { PRESTADORES.put(p.id, p); sincronizar(); }
        return m("ok", true, "estado", "PENDENTE");
    }

    static Prestador prestadorAuth(Map<String, String> f) {
        String telefone = digitos(txt(f, "telefone", 20));
        String id = txt(f, "id", 20).toUpperCase();
        String chave = !telefone.isEmpty() ? "prs:tel:" + telefone : "prs:" + id;
        verificarBloqueio(chave);
        Prestador p = null;
        if (!telefone.isEmpty()) {
            for (Prestador candidato : PRESTADORES.values())
                if (candidato.telefone.equals(telefone)) { p = candidato; break; }
        } else p = PRESTADORES.get(id); // compatibilidade com contas antigas
        String segredo = !telefone.isEmpty() ? f.getOrDefault("senha", "") : txt(f, "pin", 10);
        boolean valido = p != null && (!telefone.isEmpty()
                ? !p.senhaHash.isEmpty() && confere(segredo, p.senhaSalt, p.senhaHash)
                : confere(segredo, p.salt, p.pinHash));
        if (!valido) { falhou(chave); throw new ApiErro(401, "Telefone ou senha incorretos."); }
        sucesso(chave);
        return p;
    }

    static Object painelPrestador(Map<String, String> f) {
        Prestador pr = prestadorAuth(f);
        List<Object> jobs = new ArrayList<>();
        double retido = 0;
        if (pr.aprovado) {
            for (Pedido p : PEDIDOS.values()) {
                if (!p.prestadorId.equals(pr.id)) continue;
                if (!List.of("APROVADO", "EM_CURSO", "CONCLUIDO").contains(p.estado)) continue;
                double liquido = p.valor * (1 - TAXA);
                if (p.pagamento.equals("PARCIAL") || p.pagamento.equals("RETIDO")) retido += p.pago * (1 - TAXA);
                // Dados do cliente (nome/telefone) NUNCA são enviados ao prestador.
                jobs.add(m("codigo", p.codigo, "categoria", p.categoria, "descricao", p.descricao,
                        "bairro", p.bairro, "endereco", p.endereco, "estado", p.estado,
                        "pagamento", p.pagamento, "liquido", liquido, "avaliado", p.notaCliente > 0));
            }
        }
        return m("id", pr.id, "nome", pr.nome, "categoria", pr.categoria, "bairro", pr.bairro,
                "aprovado", pr.aprovado, "selo", pr.selo, "servicos", pr.servicos, "ganhos", pr.ganhos,
                "retido", retido, "nota", media(pr.somaNotas, pr.nNotas), "trabalhos", jobs);
    }

    static Pedido pedidoDoPrestador(Map<String, String> f, Prestador pr) {
        Pedido p = PEDIDOS.get(txt(f, "codigo", 20).toUpperCase());
        if (p == null || !p.prestadorId.equals(pr.id)) throw new ApiErro(404, "Trabalho não encontrado.");
        return p;
    }

    static Object acaoPrestador(Map<String, String> f) {
        Prestador pr = prestadorAuth(f);
        Pedido p = pedidoDoPrestador(f, pr);
        String acao = txt(f, "acao", 10);
        if (acao.equals("iniciar") && p.estado.equals("APROVADO")) {
            p.estado = "EM_CURSO";
            p.pLat = p.lat + (RND.nextDouble() - 0.5) * 0.05;
            p.pLng = p.lng + (RND.nextDouble() - 0.5) * 0.05;
            p.pEm = 0;
        } else if (acao.equals("concluir") && p.estado.equals("EM_CURSO")) {
            p.estado = "CONCLUIDO"; // agora o cliente deve pagar os 50% finais
            p.concluidoEm = System.currentTimeMillis();
        } else {
            throw new ApiErro(400, "Ação não permitida neste estado.");
        }
        return m("ok", true);
    }

    static Object posicaoPrestador(Map<String, String> f) {
        Prestador pr = prestadorAuth(f);
        Pedido p = pedidoDoPrestador(f, pr);
        if (p.estado.equals("EM_CURSO")) {
            p.pLat = num(f, "lat", p.pLat);
            p.pLng = num(f, "lng", p.pLng);
            p.pEm = System.currentTimeMillis();
        }
        return m("ok", true);
    }

    static Object avaliarCliente(Map<String, String> f) {
        Prestador pr = prestadorAuth(f);
        Pedido p = pedidoDoPrestador(f, pr);
        if (!p.estado.equals("CONCLUIDO") || p.notaCliente > 0) throw new ApiErro(400, "Avaliação não disponível.");
        p.notaCliente = nota(f);
        return m("ok", true);
    }

    // ---- Centro de Gestão
    static Object entrarGestao(Map<String, String> f) {
        verificarBloqueio("gestao");
        byte[] esperado = GESTAO_PIN.getBytes(StandardCharsets.UTF_8);
        byte[] recebido = f.getOrDefault("pin", "").getBytes(StandardCharsets.UTF_8);
        if (GESTAO_PIN.isEmpty() || !MessageDigest.isEqual(esperado, recebido)) {
            falhou("gestao");
            throw new ApiErro(401, "PIN da gestão incorrecto.");
        }
        sucesso("gestao");
        byte[] b = new byte[32]; RND.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        SESSOES_GESTAO.put(token, System.currentTimeMillis() + 4L * 3600_000L);
        return m("token", token, "expiraEm", System.currentTimeMillis() + 4L * 3600_000L);
    }

    static void gestaoAuth(Map<String, String> f) {
        String token = txt(f, "token", 100);
        Long expira = SESSOES_GESTAO.get(token);
        if (expira == null || expira < System.currentTimeMillis()) {
            SESSOES_GESTAO.remove(token);
            throw new ApiErro(401, "Sessão da gestão expirada. Entre novamente.");
        }
    }

    static Object sairGestao(Map<String, String> f) {
        SESSOES_GESTAO.remove(txt(f, "token", 100));
        return m("ok", true);
    }

    static Object painelGestao(Map<String, String> f) {
        gestaoAuth(f);
        List<Object> peds = new ArrayList<>(), prs = new ArrayList<>(), cls = new ArrayList<>();
        double retido = 0, comissao = 0;
        int abertos = 0;
        List<Pedido> lista = new ArrayList<>(PEDIDOS.values());
        Collections.reverse(lista);
        for (Pedido p : lista) {
            if (p.pagamento.equals("PARCIAL") || p.pagamento.equals("RETIDO")) retido += p.pago;
            if (p.pagamento.equals("LIBERADO")) comissao += p.valor * TAXA;
            if (!p.estado.equals("CONCLUIDO") && !p.estado.equals("CANCELADO")) abertos++;
            Prestador pr = PRESTADORES.get(p.prestadorId);
            peds.add(m("codigo", p.codigo, "modulo", p.modulo, "categoria", p.categoria, "descricao", p.descricao,
                    "bairro", p.bairro, "endereco", p.endereco, "nome", p.nome, "telefone", p.telefone,
                    "estado", p.estado, "pagamento", p.pagamento, "metodo", p.metodo, "metodo2", p.metodo2,
                    "valor", p.valor, "pago", p.pago, "restante", restante(p),
                    "prestadorId", p.prestadorId, "prestadorNome", pr == null ? "" : pr.nome,
                    "notaPrestador", p.notaPrestador, "notaCliente", p.notaCliente, "comentario", p.comentario,
                    "liberacaoEm", p.pago2Em > 0 ? p.pago2Em + RETENCAO_MS : 0));
        }
        for (Prestador pr : PRESTADORES.values()) {
            prs.add(m("id", pr.id, "nome", pr.nome, "telefone", pr.telefone, "categoria", pr.categoria,
                    "bairro", pr.bairro, "aprovado", pr.aprovado, "selo", pr.selo, "servicos", pr.servicos,
                    "nota", media(pr.somaNotas, pr.nNotas)));
        }
        for (Cliente c : CLIENTES.values()) {
            int n = 0;
            for (Pedido p : PEDIDOS.values()) if (p.clienteId.equals(c.id)) n++;
            cls.add(m("id", c.id, "nome", c.nome, "telefone", c.telefone, "pedidos", n));
        }
        return m("pedidos", peds, "prestadores", prs, "clientes", cls, "abertos", abertos, "retido", retido,
                "comissao", comissao, "taxa", TAXA, "agora", System.currentTimeMillis(),
                "bd", m("modo", BD_MODO, "motivo", BD_MOTIVO));
    }

    static Object orcamento(Map<String, String> f) {
        gestaoAuth(f);
        Pedido p = PEDIDOS.get(txt(f, "codigo", 20).toUpperCase());
        if (p == null) throw new ApiErro(404, "Pedido não encontrado.");
        if (!p.estado.equals("RECEBIDO") && !p.estado.equals("ORCAMENTO"))
            throw new ApiErro(400, "Este pedido já não aceita alterações de orçamento.");
        double valor = Math.round(num(f, "valor", 0));
        Prestador pr = PRESTADORES.get(txt(f, "prestadorId", 20));
        if (valor <= 0) throw new ApiErro(400, "Indique o valor do orçamento.");
        if (pr == null || !pr.aprovado) throw new ApiErro(400, "Escolha um prestador aprovado.");
        p.valor = valor;
        p.prestadorId = pr.id;
        p.estado = "ORCAMENTO";
        return m("ok", true);
    }

    static Object gerirPrestador(Map<String, String> f) {
        gestaoAuth(f);
        Prestador pr = PRESTADORES.get(txt(f, "id", 20));
        if (pr == null) throw new ApiErro(404, "Prestador não encontrado.");
        if (f.containsKey("aprovado")) pr.aprovado = f.get("aprovado").equals("true");
        if (f.containsKey("selo")) pr.selo = f.get("selo").equals("true");
        return m("ok", true);
    }

    static Object novoPinPrestador(Map<String, String> f) {
        gestaoAuth(f);
        Prestador pr = PRESTADORES.get(txt(f, "id", 20));
        if (pr == null) throw new ApiErro(404, "Prestador não encontrado.");
        String pin = String.format("%04d", RND.nextInt(10000));
        pr.salt = novoSal();
        pr.pinHash = hash(pin, pr.salt);
        return m("pin", pin, "nome", pr.nome);
    }

    static Object reporSenha(Map<String, String> f) {
        gestaoAuth(f);
        Cliente c = CLIENTES.get(txt(f, "id", 20));
        if (c == null) throw new ApiErro(404, "Cliente não encontrado.");
        String temp = aleatorio(8);
        c.salt = novoSal();
        c.hash = hash(temp, c.salt);
        final String id = c.id;
        SESSOES.values().removeIf(s -> s.clienteId.equals(id));
        return m("senha", temp, "nome", c.nome);
    }

    /** A gestão confirma que recebeu os 50% finais (por exemplo, o cliente pagou pela linha de pagamentos). */
    static Object confirmarFinal(Map<String, String> f) {
        gestaoAuth(f);
        Pedido p = PEDIDOS.get(txt(f, "codigo", 20).toUpperCase());
        if (p == null || !p.estado.equals("CONCLUIDO") || !p.pagamento.equals("PARCIAL"))
            throw new ApiErro(400, "Não há pagamento final pendente neste pedido.");
        String metodo = txt(f, "metodo", 20);
        fecharPagamento(p, metodo.isEmpty() ? "Gestão" : metodo);
        return m("ok", true);
    }

    static Object liberarManual(Map<String, String> f) {
        gestaoAuth(f);
        Pedido p = PEDIDOS.get(txt(f, "codigo", 20).toUpperCase());
        if (p == null || !p.estado.equals("CONCLUIDO") || !p.pagamento.equals("RETIDO"))
            throw new ApiErro(400, "Só se pode libertar quando os 100% estão pagos e retidos.");
        liberar(p);
        return m("ok", true);
    }

    static Object cancelarGestao(Map<String, String> f) {
        gestaoAuth(f);
        Pedido p = PEDIDOS.get(txt(f, "codigo", 20).toUpperCase());
        if (p == null) throw new ApiErro(404, "Pedido não encontrado.");
        if (p.estado.equals("CONCLUIDO") || p.estado.equals("CANCELADO")) throw new ApiErro(400, "Pedido já encerrado.");
        if (p.pagamento.equals("PARCIAL") || p.pagamento.equals("RETIDO")) p.pagamento = "REEMBOLSADO";
        p.estado = "CANCELADO";
        return m("ok", true);
    }

    // ================================================================== FRONT-END (uma só página)
    static String layout(String titulo, String main, String script) {
        String cfg = "{suporte:'" + TEL_SUPORTE + "',emergencia:'" + TEL_EMERGENCIA + "',pagamentos:'" + TEL_PAGAMENTOS + "'}";
        return LAYOUT.replace("{{CFG}}", cfg).replace("{{TITLE}}", titulo)
                .replace("{{MAIN}}", main).replace("{{SCRIPT}}", script);
    }

    static String paginaApp() {
        String main = HTML_INICIO + HTML_ENTRAR + HTML_PEDIDOS + HTML_PRESTADOR + HTML_GESTAO;
        String script = carregarArquivo("js/script.js");
        return layout("Serviços em 3 cliques", main, script);
    }

    static final String LAYOUT = carregarArquivo("pages/index.html");

    static String carregarArquivo(String caminho) {
        try {
            return java.nio.file.Files.readString(
                java.nio.file.Path.of(caminho), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível carregar o ficheiro: " + caminho
                + ". Execute o programa a partir da pasta principal KonektaORG.", e);
        }
    }

    // ------------------------------------------------------------------ vista: início
    static final String HTML_INICIO = """
<section class="view" id="v-inicio" hidden>
<section class="hero">
 <img class="hero-workers" src="/pages/trabalhadores.png" alt="Dois trabalhadores com ferramentas, prontos para prestar serviços">
 <div class="wrap hero-grid">
  <div>
   <p class="eyebrow">BEM-VINDO AO KONEKTA</p>
   <h1>Encontra o serviço que precisas.</h1>
   <p class="lead">Profissionais qualificados e serviços perto de ti, em Nampula.</p>
   <p><a class="btn" href="#pedir">Pedir um serviço</a></p>
   <p class="small" id="ultimo"></p>
  </div>
  <div class="card wiz" id="pedir">
   <div class="dots" aria-hidden="true"><span class="dot on" data-step="1"></span><span class="dot" data-step="2"></span><span class="dot" data-step="3"></span></div>

   <div class="step" data-step="1">
    <h2>O que precisa?</h2>
    <div class="mods">
     <button type="button" class="mod" data-m="TECNICA"><strong>Técnica</strong><span>Eletricidade, canalização, informática…</span></button>
     <button type="button" class="mod" data-m="LOG"><strong>Logística</strong><span>Comida, mercado e transporte de cargas</span></button>
     <button type="button" class="mod" data-m="SAUDE"><strong>Saúde</strong><span>Consultas online e psicologia</span></button>
    </div>
    <div id="cats" class="chips" hidden></div>
   </div>

   <div class="step" data-step="2" hidden>
    <h2 id="t2">Detalhes do pedido</h2>
    <label>Descreva o que precisa
     <textarea id="descricao" rows="3" maxlength="500" placeholder="Ex.: o quadro elétrico faz faísca quando ligo o ar condicionado"></textarea></label>
    <div class="row2">
     <label>Bairro<input id="bairro" maxlength="60" placeholder="Ex.: Muatala" autocomplete="off"></label>
     <label>Endereço ou ponto de referência<input id="endereco" maxlength="120" autocomplete="off"></label>
    </div>
    <button type="button" class="btn ghost sm" id="gps">Usar a minha localização</button>
    <span class="small" id="gpsok"></span>
    <p class="small" id="contaLinha" style="margin-top:12px"></p>
    <div class="actions"><button type="button" class="btn ghost" id="v1">Voltar</button><button type="button" class="btn" id="n2">Continuar</button></div>
   </div>

   <div class="step" data-step="3" hidden>
    <h2>Confirme o pedido</h2>
    <dl class="resumo" id="resumo"></dl>
    <p class="small">Depois de enviar, a gestão prepara o orçamento. Só paga se aprovar: 50% ao aprovar e 50% no fim do trabalho. O prestador nunca recebe o seu contacto.</p>
    <div class="actions"><button type="button" class="btn ghost" id="v2">Voltar</button><button type="button" class="btn sun" id="enviar">Enviar pedido</button></div>
   </div>

   <div class="step" data-step="4" hidden>
    <h2>Pedido enviado</h2>
    <p>Guarde este código. Também o encontra em <b>Meus pedidos</b>.</p>
    <div class="codigo" id="okcodigo"></div>
    <div class="actions"><a class="btn" id="oklink" href="#/pedidos">Acompanhar pedido</a><button type="button" class="btn ghost" id="novo">Novo pedido</button></div>
   </div>
  </div>
 </div>
</section>

<section class="sec">
 <div class="wrap">
  <h2>Como funciona</h2>
  <ol class="passos">
   <li><h3>Pede</h3><p>Escolhe o serviço e descreve o problema.</p></li>
   <li><h3>Gestão prepara</h3><p>O Centro de Gestão define o orçamento e escolhe o prestador.</p></li>
   <li><h3>Paga 50%</h3><p>Aprova o orçamento e paga metade. O KONEKTA Pay guarda o valor.</p></li>
   <li><h3>Paga os outros 50%</h3><p>Com o serviço feito, paga a outra metade. O prestador recebe 24 horas depois.</p></li>
  </ol>
 </div>
</section>

<section class="sec" style="padding-top:0">
 <div class="wrap">
  <h2>O que a KONEKTA faz por si</h2>
  <div class="mdl">
   <div><h3>Técnica KONEKTA</h3><p>Eletricidade, mecânica, informática, construção, canalização, pintura, gelo e mais.</p><p><b>Orçamento aprovado pela gestão antes.</b> Garantia de 30 dias.</p></div>
   <div><h3>Log KONEKTA</h3><p>Delivery de comida e mercado. Transporte de cargas leves e pesadas.</p><p><b>Motoboy, carrinha ou camião.</b> Rastreio em tempo real.</p></div>
   <div><h3>Saúde KONEKTA</h3><p>Consulta geral e psicologia por videochamada ou chamada.</p><p><b>Marcação rápida,</b> com a gestão a confirmar o profissional.</p></div>
  </div>
 </div>
</section>

<section class="sec regra">
 <div class="wrap">
  <h2>Pagamento seguro: KONEKTA Pay</h2>
  <p>Cliente e prestador não conversam diretamente. Tudo passa pelo Centro de Gestão KONEKTA. O seu dinheiro só chega ao prestador depois do serviço feito.</p>
  <div class="metade"><div><b>50%</b>ao aprovar o orçamento, para o trabalho começar.</div><div><b>50%</b>quando o serviço estiver concluído.</div></div>
  <div class="fluxo"><span>Cliente</span><i>&#8596;</i><span>Centro de Gestão</span><i>&#8596;</i><span>Prestador</span></div>
 </div>
</section>

<section class="sec">
 <div class="wrap duas">
  <div>
   <h2>Mapa inteligente</h2>
   <p>Veja no mapa onde está o técnico ou o entregador, em tempo real. Alterne entre vista de estrada e imagem de satélite no botão do mapa.</p>
   <p class="small">Pontos de exemplo em Nampula.</p>
  </div>
  <div id="mapa-demo" class="mapbox" role="img" aria-label="Mapa de Nampula"></div>
 </div>
</section>

<section class="sec" style="background:rgba(239,237,227,.55)">
 <div class="wrap duas">
  <div>
   <h2>Trabalhe com a KONEKTA</h2>
   <ul class="lista">
    <li>Receba clientes sem ter de os procurar.</li>
    <li>Ganhe o selo <span class="tag selo">Prestador KONEKTA</span> depois do curso rápido.</li>
    <li>Painel com os seus serviços e ganhos. Os dados do cliente ficam sempre protegidos.</li>
   </ul>
   <a class="btn" href="#/prestador">Área do prestador</a>
  </div>
  <div class="card">
   <h3>A sua conta de cliente</h3>
   <p>Entre com o seu telefone e uma senha para fazer pedidos e ver o estado de cada um. Só você vê os seus pedidos.</p>
   <div class="actions"><a class="btn" href="#/entrar">Entrar ou criar conta</a><a class="btn ghost" href="#/pedidos">Meus pedidos</a></div>
  </div>
 </div>
</section>
</section>
""";

    // ------------------------------------------------------------------ vista: conta do cliente
    static final String HTML_ENTRAR = """
<section class="view" id="v-entrar" hidden>
<div class="wrap sec" style="max-width:560px">
 <div id="visitante">
  <h1 style="font-size:clamp(1.8rem,6vw,2.6rem)">A sua conta KONEKTA</h1>
  <p>O seu telefone é o seu utilizador. Só você vê os seus pedidos.</p>
  <div class="tabs"><button class="tab on" data-t="entrar">Entrar</button><button class="tab" data-t="criar">Criar conta</button></div>
  <div class="card" id="cTabEntrar">
   <form id="fEntrar">
    <label>Telefone<input id="etel" type="tel" inputmode="tel" autocomplete="tel" required></label>
    <label>Senha<input id="esenha" type="password" autocomplete="current-password" required></label>
    <button class="btn" type="submit">Entrar</button>
   </form>
   <p class="small" style="margin:12px 0 0">Esqueceu a senha? Ligue para o suporte KONEKTA e a gestão cria uma senha temporária.</p>
  </div>
  <div class="card" id="cTabCriar" hidden>
   <form id="fCriar">
    <label>Nome completo<input id="cnome" maxlength="60" autocomplete="name" required></label>
    <label>Telefone<input id="ctel" type="tel" inputmode="tel" autocomplete="tel" placeholder="84 000 0000" required></label>
    <label>Senha (mínimo 6 caracteres)<input id="csenha" type="password" autocomplete="new-password" minlength="6" required></label>
    <label>Repita a senha<input id="csenha2" type="password" autocomplete="new-password" minlength="6" required></label>
    <button class="btn" type="submit">Criar conta</button>
   </form>
  </div>
 </div>
 <div id="cConta" hidden>
  <h1 style="font-size:clamp(1.8rem,6vw,2.6rem)" id="oi"></h1>
  <p id="otel"></p>
  <div class="actions" style="margin-bottom:22px"><a class="btn" href="#/pedidos">Meus pedidos</a><a class="btn sun" href="#pedir">Novo pedido</a></div>
  <div class="card">
   <h3>Alterar senha</h3>
   <form id="fSenha">
    <label>Senha atual<input id="satual" type="password" autocomplete="current-password" required></label>
    <label>Nova senha (mínimo 6)<input id="snova" type="password" autocomplete="new-password" minlength="6" required></label>
    <button class="btn" type="submit">Guardar nova senha</button>
   </form>
  </div>
  <p style="margin-top:18px"><button class="btn danger" id="cSair">Sair da conta</button></p>
 </div>
</div>
</section>
""";

    // ------------------------------------------------------------------ vista: meus pedidos
    static final String HTML_PEDIDOS = """
<section class="view" id="v-pedidos" hidden>
<div class="wrap sec" style="max-width:720px">
 <h1 style="font-size:clamp(1.8rem,6vw,2.6rem)">Meus pedidos</h1>

 <div id="semConta" class="card" hidden>
  <p>Entre na sua conta para ver e acompanhar os seus pedidos.</p>
  <button class="btn" id="irEntrar">Entrar ou criar conta</button>
 </div>

 <div id="lista" hidden>
  <div id="listaItens"></div>
  <a class="btn sun" href="#pedir">Novo pedido</a>
 </div>

 <div id="erro" class="card" hidden><p id="erroTxt"></p><a class="btn ghost" href="#/pedidos">Ver os meus pedidos</a></div>

 <div id="pdPainel" hidden>
  <p><a href="#/pedidos">&larr; Todos os pedidos</a></p>
  <div class="card">
   <div style="display:flex;justify-content:space-between;gap:8px;flex-wrap:wrap;align-items:center">
    <div><span class="tag" id="pMod"></span> <b id="pCat"></b></div><span class="codigo" style="font-size:1.2rem;margin:0;padding:4px 12px" id="pCod"></span>
   </div>
   <p id="pDesc" style="margin:10px 0 4px"></p>
   <p class="small" id="pBairro"></p>
   <ul class="tl" id="tl"></ul>
   <div id="acao"></div>
   <p class="small" id="pPrest"></p>
  </div>
  <div id="mapaBox" style="margin-top:16px" hidden>
   <h2 style="font-size:1.3rem">Onde está o prestador</h2>
   <div id="mapa" class="mapbox"></div>
   <p class="small">A posição atualiza automaticamente a cada 5 segundos.</p>
  </div>
 </div>
</div>
</section>
""";

    // ------------------------------------------------------------------ vista: prestador
    static final String HTML_PRESTADOR = """
<section class="view" id="v-prestador" hidden>
<div class="wrap sec" style="max-width:820px">
 <div id="entrada">
  <h1 style="font-size:clamp(1.8rem,6vw,2.6rem)">Área do prestador</h1>
  <p>Receba pedidos aprovados pela KONEKTA. O cliente paga 50% antes e 50% no fim do trabalho. A KONEKTA retém <b>15%</b> e o seu pagamento é libertado 24 horas depois do pagamento final. Nunca vê o nome nem o telefone do cliente.</p>
  <div class="tabs"><button class="tab on" data-t="entrar">Entrar</button><button class="tab" data-t="registar">Registar</button></div>
  <div class="card" id="prTabEntrar">
   <form id="fLogin">
    <label>Telefone<input id="lid" type="tel" inputmode="tel" autocomplete="username" required></label>
    <label>Senha<input id="lpin" type="password" autocomplete="current-password" required></label>
    <button class="btn" type="submit">Entrar</button>
   </form>
   <p class="small" style="margin:12px 0 0">Esqueceu o PIN? Ligue para o suporte KONEKTA.</p>
  </div>
  <div class="card" id="prTabReg" hidden>
   <form id="fReg">
    <label>Nome completo<input id="rnome" maxlength="60" required></label>
    <label>Telefone<input id="rtel" type="tel" inputmode="tel" required></label>
    <label>Senha (mínimo 8 caracteres)<input id="rsenha" type="password" minlength="8" autocomplete="new-password" required></label>
    <label>Confirmar senha<input id="rsenha2" type="password" minlength="8" autocomplete="new-password" required></label>
    <label>O que faz<select id="rcat"></select></label>
    <label>Bairro<input id="rbairro" maxlength="60" required></label>
    <button class="btn" type="submit">Pedir registo</button>
   </form>
   <div id="regOk" class="resumo" hidden></div>
  </div>
 </div>

 <div id="prPainel" hidden>
  <div style="display:flex;justify-content:space-between;gap:10px;flex-wrap:wrap;align-items:center;margin-bottom:12px">
   <h1 style="font-size:clamp(1.6rem,5.5vw,2.2rem);margin:0" id="prNome"></h1>
   <button class="btn ghost sm" id="prSair">Sair</button>
  </div>
  <p id="prInfo"></p>
  <div id="prAviso" class="aviso" hidden>O seu registo está a ser analisado pela gestão. Vai poder receber trabalhos assim que for aprovado.</div>
  <div class="stats" id="prStats"></div>
  <h2 style="font-size:1.3rem">Os meus trabalhos</h2>
  <div id="jobs"></div>
 </div>
</div>
</section>
""";

    // ------------------------------------------------------------------ vista: Centro de Gestão
    static final String HTML_GESTAO = """
<section class="view" id="v-gestao" hidden>
<div class="wrap sec">
 <div id="gLogin" style="max-width:420px">
  <h1 style="font-size:clamp(1.8rem,6vw,2.6rem)">Centro de Gestão</h1>
  <div class="card">
   <form id="fPin">
    <label>PIN da gestão<input id="pin" type="password" inputmode="numeric" required></label>
    <button class="btn" type="submit">Entrar</button>
   </form>
  </div>
 </div>
 <div id="gApp" hidden>
  <div style="display:flex;justify-content:space-between;gap:10px;flex-wrap:wrap;align-items:center;margin-bottom:12px">
   <h1 style="font-size:clamp(1.6rem,5.5vw,2.2rem);margin:0">Centro de Gestão</h1>
   <div class="actions" style="margin:0"><button class="btn ghost sm" id="atualizar">Atualizar</button><button class="btn ghost sm" id="gSair">Sair</button></div>
  </div>
  <div id="avisoBd" class="aviso" hidden></div>
  <p class="small" id="bdOk"></p>
  <div class="stats" id="gStats"></div>
  <div class="tabs"><button class="tab on" data-t="ped">Pedidos</button><button class="tab" data-t="prs">Prestadores</button><button class="tab" data-t="cli">Clientes</button></div>
  <div id="vPed"></div>
  <div id="vPrs" hidden></div>
  <div id="vCli" hidden></div>
 </div>
</div>
</section>
""";

    }
