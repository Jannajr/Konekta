$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
function Test-Assert($condition, $message) { if (-not $condition) { throw $message }; Write-Host "[OK] $message" -ForegroundColor Green }
Write-Host '=== TESTE DE INTEGRACAO KONEKTA ===' -ForegroundColor Cyan
try {
  $home = Invoke-WebRequest "$base/" -UseBasicParsing
  Test-Assert ($home.StatusCode -eq 200 -and $home.Content.Length -gt 100) 'Página inicial'
  Test-Assert ((Invoke-WebRequest "$base/css/style.css" -UseBasicParsing).StatusCode -eq 200) 'CSS'
  Test-Assert ((Invoke-WebRequest "$base/js/script.js" -UseBasicParsing).StatusCode -eq 200) 'JavaScript'

  $phone = '86' + (Get-Random -Minimum 1000000 -Maximum 9999999)
  $client = Invoke-RestMethod -Method Post -Uri "$base/api/cliente/registar" -ContentType 'application/x-www-form-urlencoded' -Body @{nome='Cliente Teste'; telefone=$phone; senha='Teste12345'}
  Test-Assert ([bool]$client.token) 'Registo de cliente e emissão de sessão'
  $me = Invoke-RestMethod -Method Post -Uri "$base/api/cliente/eu" -ContentType 'application/x-www-form-urlencoded' -Body @{token=$client.token}
  Test-Assert ($me.telefone -eq $phone) 'Autenticação da sessão do cliente'
  $bad = $false
  try { Invoke-RestMethod -Method Post -Uri "$base/api/cliente/eu" -ContentType 'application/x-www-form-urlencoded' -Body @{token='token-invalido'} | Out-Null } catch { $bad = $true }
  Test-Assert $bad 'Bloqueio de sessão inválida'

  $order = Invoke-RestMethod -Method Post -Uri "$base/api/pedidos" -ContentType 'application/x-www-form-urlencoded' -Body @{token=$client.token; modulo='TECNICA'; categoria='Electricidade'; descricao='Teste de integração da instalação eléctrica'; bairro='Muatala'; endereco='Rua de teste'}
  Test-Assert ([bool]$order.codigo) 'Criação de pedido autenticado'

  $phoneP = '87' + (Get-Random -Minimum 1000000 -Maximum 9999999)
  $provider = Invoke-RestMethod -Method Post -Uri "$base/api/prestadores" -ContentType 'application/x-www-form-urlencoded' -Body @{nome='Prestador Teste'; telefone=$phoneP; senha='Teste12345'; categoria='Electricidade'; bairro='Muatala'}
  Test-Assert ($provider.estado -eq 'PENDENTE') 'Registo de prestador pendente de aprovação'

  $pin = $env:KONEKTA_PIN
  if (-not $pin) { $pin = '2026' }
  $admin = Invoke-RestMethod -Method Post -Uri "$base/api/gestao/entrar" -ContentType 'application/x-www-form-urlencoded' -Body @{pin=$pin}
  Test-Assert ([bool]$admin.token) 'Login da gestão e emissão de sessão temporária'
  $panel = Invoke-RestMethod -Method Post -Uri "$base/api/gestao/painel" -ContentType 'application/x-www-form-urlencoded' -Body @{token=$admin.token}
  Test-Assert ($null -ne $panel.pedidos) 'Acesso ao painel com sessão da gestão'
  $denied = $false
  try { Invoke-RestMethod -Method Post -Uri "$base/api/gestao/painel" -ContentType 'application/x-www-form-urlencoded' -Body @{pin=$pin} | Out-Null } catch { $denied = $true }
  Test-Assert $denied 'PIN não é aceite directamente para operações administrativas'
  $found = @($panel.pedidos | Where-Object { $_.codigo -eq $order.codigo }).Count -gt 0
  Test-Assert $found 'Pedido de teste visível no painel de gestão'

  Write-Host ''
  Write-Host 'TESTES DE INTEGRACAO CONCLUIDOS. Foram criados dados de teste na base de dados.' -ForegroundColor Green
  Write-Host 'Ainda falta validar manualmente: orçamento, aprovação do cliente, acções do prestador, pagamentos simulados, avaliações e recuperação de senha.' -ForegroundColor Yellow
} catch {
  Write-Host "[ERRO] $($_.Exception.Message)" -ForegroundColor Red
  Write-Host 'Confirma que o servidor está ligado em http://localhost:8080 e que usas uma base de dados de teste.' -ForegroundColor Yellow
  exit 1
}
