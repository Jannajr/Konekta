# KONEKTA — versão de teste local

Plataforma Java para intermediação de serviços em Nampula. Esta versão mantém o código existente, o design simples, os módulos de cliente/prestador/gestão, a API, e a persistência SQLite com alternativa em ficheiro.

## Requisitos
- JDK 17 ou superior (JDK 21 recomendado).
- Windows: PowerShell e navegador moderno.
- A primeira execução pode precisar de Internet para descarregar o driver SQLite. Se não estiver disponível, a aplicação tenta usar `konekta-dados.txt`.

## Executar no Windows
1. Extrai a pasta ZIP.
2. Abre a pasta `KonektaORG-organizado` no VS Code.
3. Abre Terminal → New Terminal e confirma que estás na pasta principal (a que contém `src`, `pages`, `css` e `js`).
4. Executa `java -version`.
5. Executa `java src/Konekta.java` ou dá duplo clique em `iniciar-windows.bat`.
6. Abre `http://localhost:8080`.
7. Mantém o terminal aberto enquanto usas o site. `Ctrl+C` termina o servidor.

## Teste automático rápido (Windows)
Com o servidor a correr, abre outro terminal PowerShell na pasta principal e executa:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\testar-konekta.ps1
```

O teste verifica a página, CSS, JavaScript, registo de cliente e registo de prestador pendente. Cria registos de teste na base de dados; não o executes numa base de dados com dados de produção.

## Contas de demonstração
O programa pode criar prestadores de demonstração `PRS-0001/1111`, `PRS-0002/2222` e `PRS-0003/3333`. O PIN de gestão predefinido é `2026`.

**Não publiques esta configuração na Internet.** Antes de publicar, define um PIN administrativo forte através da variável de ambiente `KONEKTA_PIN`, remove/desactiva as contas de demonstração, activa HTTPS, revê sessões e autorização, limita tentativas, configura cópias de segurança e testa a base de dados persistente.

## Dados locais
- `konekta.db`: base SQLite, quando o driver está disponível.
- `konekta-dados.txt`: alternativa em ficheiro.
Não apagues estes ficheiros se contiverem registos que queres manter. Faz uma cópia antes de actualizar o código.

## Limites importantes
- Este pacote é para desenvolvimento e testes locais; não é uma implantação de produção certificada.
- Os pagamentos mostrados pela aplicação são estados internos de demonstração, não confirmação de transacções reais de M-Pesa/e-Mola/mKesh.
- Antes de aceitar clientes reais, é necessário rever a segurança, privacidade, autenticação da gestão, autorização de cada endpoint, recuperação de senha, concorrência e migração da base de dados.

## Publicação na Internet
Foi acrescentada uma configuração Docker Compose com Caddy para HTTPS. Consulte `PUBLICACAO-INTERNET.md` e copie `.env.example` para `.env` antes de configurar o domínio e o segredo administrativo.

**Importante:** a configuração de alojamento não significa que a aplicação tenha sido certificada para produção. A gestão ainda envia o PIN em cada pedido; por isso, mantenha o site em ambiente de teste até essa autenticação ser substituída por uma sessão administrativa segura e os fluxos completos serem validados.
