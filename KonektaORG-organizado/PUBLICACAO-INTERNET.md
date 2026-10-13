# Publicação da KONEKTA na Internet (Docker + HTTPS)

## Antes de publicar
- Ter um servidor Linux/VPS com Docker e Docker Compose instalados.
- Ter um domínio (por exemplo, `app.seudominio.co.mz`) e configurar os registos DNS A/AAAA para o IP público do servidor.
- Abrir as portas TCP 80 e 443 no firewall.
- Confirmar que a aplicação foi revista e testada num ambiente de pré-produção.

## Instalação
1. Copie o projecto para o servidor.
2. Copie `.env.example` para `.env`.
3. Edite `.env`: coloque o domínio real e um `KONEKTA_PIN` aleatório com pelo menos 16 caracteres (recomendados 32 ou mais). Não partilhe nem publique o `.env`.
4. Execute `docker compose config` para verificar a configuração.
5. Execute `docker compose up -d --build`.
6. Consulte `docker compose logs -f konekta caddy`.
7. Quando o DNS estiver correcto, Caddy tenta obter e renovar automaticamente o certificado HTTPS.
8. Abra `https://SEU-DOMINIO`.

## Dados e cópias de segurança
A base SQLite fica no volume Docker `konekta_data`. Faça cópias de segurança regulares e teste a restauração. Não elimine o volume ao actualizar a aplicação. Para parar: `docker compose down` (não use `-v` se quiser conservar os dados).

## Avisos de segurança e limites
- O modo de produção recusa arrancar se o PIN da gestão não for configurado correctamente e desactiva as contas de demonstração.
- Esta configuração põe HTTPS à frente da aplicação, mas **não é uma certificação de segurança**. Antes de aceitar utilizadores reais, é obrigatória uma auditoria dos fluxos de autenticação/autorização, sessões, limites de pedidos, privacidade e recuperação de contas.
- A gestão agora usa token de sessão temporário (4 horas) após o login, em vez de reenviar o PIN em cada operação. A sessão é guardada em memória e termina quando o servidor reinicia; por isso, use uma única instância e mantenha o processo actualizado.
- Os pagamentos são estados internos de demonstração. Não há integração real confirmada com M-Pesa, e-Mola ou mKesh.
- A localização do prestador é simulada; não é rastreamento GPS real.
- Não guardar dados pessoais reais até validar a política de privacidade, consentimento, retenção e cópias de segurança.
