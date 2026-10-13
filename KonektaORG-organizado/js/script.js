registar('inicio',root=>{
 const $=s=>root.querySelector(s),$$=(s,r=root)=>[...r.querySelectorAll(s)];
 let sel={modulo:'',categoria:'',lat:'',lng:''};
 function passo(n){
  $$('.step').forEach(s=>s.hidden=Number(s.dataset.step)!==n);
  $$('.dot').forEach(d=>d.classList.toggle('on',Number(d.dataset.step)<=Math.min(n,3)));
  if(n===3)$('#enviar').textContent=TOKEN()?'Enviar pedido':'Entrar e enviar pedido';
  if(n===2)$('#contaLinha').textContent=TOKEN()?('Pedido em nome de '+(localStorage.getItem('konekta_nome')||'')+'.'):'No fim vai entrar na sua conta (ou criar uma, é rápido).';
 }
 $$('.mod').forEach(b=>b.onclick=()=>{
  sel.modulo=b.dataset.m;
  $$('.mod').forEach(x=>x.classList.toggle('on',x===b));
  const c=$('#cats');c.hidden=false;
  c.innerHTML=CATS[sel.modulo].cats.map(x=>'<button type="button" class="chip">'+esc(x)+'</button>').join('');
  $$('.chip',c).forEach(ch=>ch.onclick=()=>{sel.categoria=ch.textContent;$('#t2').textContent=sel.categoria;passo(2);});
  c.scrollIntoView({behavior:'smooth',block:'nearest'});
 });
 $('#v1').onclick=()=>passo(1);
 $('#v2').onclick=()=>passo(2);
 $('#gps').onclick=()=>{
  if(!navigator.geolocation){$('#gpsok').textContent='O telemóvel não permite localização.';return;}
  $('#gpsok').textContent='A procurar…';
  navigator.geolocation.getCurrentPosition(p=>{sel.lat=p.coords.latitude;sel.lng=p.coords.longitude;$('#gpsok').textContent='Localização guardada.';},
   ()=>{$('#gpsok').textContent='Não foi possível. Vamos usar o bairro indicado.';},{enableHighAccuracy:true,timeout:10000});
 };
 function montarResumo(){
  $('#resumo').innerHTML='<dt>Serviço</dt><dd>'+esc(NOMEMOD[sel.modulo])+' · '+esc(sel.categoria)+'</dd>'
   +'<dt>Pedido</dt><dd>'+esc($('#descricao').value)+'</dd>'
   +'<dt>Local</dt><dd>'+esc($('#bairro').value)+($('#endereco').value?' · '+esc($('#endereco').value):'')+(sel.lat?' (localização GPS)':'')+'</dd>';
 }
 $('#n2').onclick=()=>{
  if($('#descricao').value.trim().length<5)return toast('Descreva o pedido com mais detalhe.',true);
  if(!$('#bairro').value.trim())return toast('Indique o bairro.',true);
  montarResumo();passo(3);
 };
 function guardarRascunho(){sessionStorage.setItem('konekta_rascunho',JSON.stringify({sel:sel,d:$('#descricao').value,b:$('#bairro').value,e:$('#endereco').value}));}
 function restaurar(){
  try{
   const r=JSON.parse(sessionStorage.getItem('konekta_rascunho')||'null');
   if(!r||!TOKEN())return;
   sel=r.sel;$('#descricao').value=r.d;$('#bairro').value=r.b;$('#endereco').value=r.e;
   $('#t2').textContent=sel.categoria;montarResumo();passo(3);
   sessionStorage.removeItem('konekta_rascunho');
   setTimeout(()=>document.getElementById('pedir').scrollIntoView(),50);
  }catch(e){}
 }
 $('#enviar').onclick=async()=>{
  if(!TOKEN()){guardarRascunho();sessionStorage.setItem('konekta_next','pedir');ir('entrar');return;}
  const b=$('#enviar');b.disabled=true;
  try{
   const r=await api('/api/pedidos',{modulo:sel.modulo,categoria:sel.categoria,descricao:$('#descricao').value,bairro:$('#bairro').value,
     endereco:$('#endereco').value,lat:sel.lat,lng:sel.lng});
   $('#okcodigo').textContent=r.codigo;
   $('#oklink').href='#/pedidos/'+encodeURIComponent(r.codigo);
   passo(4);
  }catch(e){toast(e.message,true);}finally{b.disabled=false;}
 };
 $('#novo').onclick=()=>{sel={modulo:'',categoria:'',lat:'',lng:''};$$('input,textarea').forEach(i=>i.value='');$('#cats').hidden=true;$$('.mod').forEach(x=>x.classList.remove('on'));$('#gpsok').textContent='';passo(1);};
 const m=mapa('mapa-demo',[-15.1165,39.2666],13);
 [['Praça dos Trabalhadores',-15.1170,39.2660,'Ponto central'],['Muatala',-15.1040,39.2790,'Técnico de eletricidade'],['Napipine',-15.1230,39.2580,'Técnico de informática']]
  .forEach(p=>L.marker([p[1],p[2]]).addTo(m).bindPopup('<b>'+p[0]+'</b><br>'+p[3]));
 return {show(){
  $('#ultimo').innerHTML=TOKEN()?'<a href="#/pedidos">Ver os meus pedidos</a>':'';
  restaurar();
  const ativo=$$('.step').find(s=>!s.hidden);
  if(ativo&&ativo.dataset.step==='3')passo(3);
  if(ativo&&ativo.dataset.step==='2')passo(2);
 }};
});
;
registar('entrar',root=>{
 const $=s=>root.querySelector(s),$$=(s,r=root)=>[...r.querySelectorAll(s)];
 function depois(){
  const n=sessionStorage.getItem('konekta_next')||'inicio';
  sessionStorage.removeItem('konekta_next');
  ir(n);
 }
 function mostrar(){
  const on=!!TOKEN();
  $('#visitante').hidden=on;$('#cConta').hidden=!on;
  if(on){
   api('/api/cliente/eu').then(d=>{$('#oi').textContent='Olá, '+d.nome;$('#otel').textContent='Telefone: '+d.telefone;localStorage.setItem('konekta_nome',d.nome);atualizarNav();})
    .catch(()=>{mostrar();});
  }
 }
 $$('.tab').forEach(t=>t.onclick=()=>{$$('.tab').forEach(x=>x.classList.toggle('on',x===t));$('#cTabEntrar').hidden=t.dataset.t!=='entrar';$('#cTabCriar').hidden=t.dataset.t!=='criar';});
 $('#fEntrar').onsubmit=async e=>{e.preventDefault();
  try{const r=await api('/api/cliente/entrar',{telefone:$('#etel').value,senha:$('#esenha').value});guardarSessao(r);$('#fEntrar').reset();depois();}
  catch(er){toast(er.message,true);}};
 $('#fCriar').onsubmit=async e=>{e.preventDefault();
  if($('#csenha').value!==$('#csenha2').value)return toast('As senhas não são iguais.',true);
  try{const r=await api('/api/cliente/registar',{nome:$('#cnome').value,telefone:$('#ctel').value,senha:$('#csenha').value});guardarSessao(r);$('#fCriar').reset();depois();}
  catch(er){toast(er.message,true);}};
 $('#fSenha').onsubmit=async e=>{e.preventDefault();
  try{await api('/api/cliente/senha',{atual:$('#satual').value,nova:$('#snova').value});toast('Senha alterada.');$('#fSenha').reset();}
  catch(er){toast(er.message,true);}};
 $('#cSair').onclick=async()=>{try{await api('/api/cliente/sair');}catch(e){}limparSessao();ir('inicio');};
 return {show(){mostrar();}};
});
;
registar('pedidos',root=>{
 const $=s=>root.querySelector(s),$$=(s,r=root)=>[...r.querySelectorAll(s)];
 const FLUXO=[['RECEBIDO','Pedido recebido'],['ORCAMENTO','Orçamento pronto'],['APROVADO','Entrada de 50% paga'],['EM_CURSO','Prestador em serviço'],['CONCLUIDO','Serviço concluído'],['PAGO_FINAL','Pagamento final de 50% feito'],['LIBERADO','Pagamento libertado ao prestador']];
 let timer=null,mp=null,mkP=null,CODIGO='',notaSel=0;
 function esconder(){['#semConta','#lista','#erro','#pdPainel'].forEach(s=>$(s).hidden=true);}
 function parar(){clearInterval(timer);timer=null;}
 function limparMapa(){if(mp){mp.remove();mp=null;mkP=null;}$('#mapaBox').hidden=true;$('#acao').dataset.estado='';}
 $('#irEntrar').onclick=()=>{sessionStorage.setItem('konekta_next','pedidos');ir('entrar');};

 async function listar(){
  try{
   const d=await api('/api/cliente/pedidos');
   esconder();$('#lista').hidden=false;
   const box=$('#listaItens');
   if(!d.pedidos.length){box.innerHTML='<p>Ainda não fez pedidos.</p>';return;}
   box.innerHTML=d.pedidos.map(p=>'<a class="job" style="display:block;text-decoration:none;color:inherit" href="#/pedidos/'+encodeURIComponent(p.codigo)+'"><div class="top2"><h3>'+esc(p.categoria)+'</h3><span class="tag'+(p.estado==='CONCLUIDO'&&p.pagamento==='PARCIAL'?' warn':'')+'">'+esc(etiqueta(p.estado,p.pagamento))+'</span></div><p class="small" style="margin:0">'+esc(p.codigo)+' · '+esc(NOMEMOD[p.modulo])+(p.valor?' · '+MT(p.valor):'')+' · '+new Date(p.criadoEm).toLocaleDateString('pt-PT')+'</p></a>').join('');
  }catch(e){
   if(!TOKEN()){esconder();$('#semConta').hidden=false;return;}
   toast(e.message,true);
  }
 }
 async function carregar(){
  try{
   const d=await api('/api/acompanhar',{codigo:CODIGO});
   $('#erro').hidden=true;$('#pdPainel').hidden=false;
   render(d);
  }catch(e){
   parar();
   if(!TOKEN()){esconder();$('#semConta').hidden=false;return;}
   $('#pdPainel').hidden=true;$('#erro').hidden=false;$('#erroTxt').textContent=e.message;
  }
 }
 function render(d){
  $('#pMod').textContent=NOMEMOD[d.modulo];$('#pCat').textContent=d.categoria;$('#pCod').textContent=d.codigo;
  $('#pDesc').textContent=d.descricao;$('#pBairro').textContent='Bairro: '+d.bairro;
  if(d.estado==='CANCELADO'){$('#tl').innerHTML='<li class="now">Pedido cancelado</li>';}
  else{
   let idx=FLUXO.findIndex(x=>x[0]===d.estado);
   if(d.estado==='CONCLUIDO'&&d.pagamento==='RETIDO')idx=5;
   if(d.pagamento==='LIBERADO')idx=6;
   $('#tl').innerHTML=FLUXO.map((x,i)=>'<li class="'+(i<idx?'done':i===idx?'done now':'')+'">'+x[1]+'</li>').join('');
  }
  $('#pPrest').textContent=d.prestador?('Prestador: '+d.prestador.nome+(d.prestador.selo?' · Selo KONEKTA':'')+(d.prestador.nota?' · nota '+d.prestador.nota:'')+'. O contacto é gerido pelo Centro de Gestão.'):'';
  const a=$('#acao');
  const chave=d.estado+'|'+d.pagamento+'|'+d.avaliado;
  if(a.dataset.estado!==chave){
   a.dataset.estado=chave;
   const ajudaPag='<p class="small" style="margin-top:10px">Dúvidas sobre pagamentos? Ligue <a href="'+tl(KT.pagamentos)+'">'+esc(KT.pagamentos)+'</a>.</p>';
   if(d.estado==='ORCAMENTO'){
    a.innerHTML='<div class="resumo"><b>Orçamento total: '+MT(d.valor)+'</b><br><span class="small">Paga '+MT(d.entrada)+' (50%) agora e '+MT(d.restante)+' (50%) quando o serviço estiver concluído. Garantia de 30 dias. O dinheiro fica retido no KONEKTA Pay.</span></div>'
     +METODOS+'<div class="actions"><button class="btn sun" id="pagar">Aprovar e pagar 50% ('+MT(d.entrada)+')</button><button class="btn danger" id="canc">Cancelar pedido</button></div>'+ajudaPag;
    $('#pagar').onclick=async()=>{try{await api('/api/aceitar',{codigo:CODIGO,metodo:$('.met',a).value});toast('Entrada de 50% paga e retida. Serviço confirmado.');carregar();}catch(e){toast(e.message,true);}};
    $('#canc').onclick=cancelar;
   }else if(d.estado==='RECEBIDO'){
    a.innerHTML='<p>A gestão está a preparar o seu orçamento. Volte em alguns minutos.</p><button class="btn danger sm" id="canc">Cancelar pedido</button>';
    $('#canc').onclick=cancelar;
   }else if(d.estado==='APROVADO'){
    a.innerHTML='<p>Entrada de <b>'+MT(d.pago)+'</b> (50%) paga e retida com segurança. O prestador vai iniciar o serviço. Os restantes <b>'+MT(d.restante)+'</b> só são pagos quando o serviço estiver concluído.</p>'+ajudaPag;
   }else if(d.estado==='EM_CURSO'){
    a.innerHTML='<p>O serviço está em curso. Veja a posição no mapa. No fim, pague os restantes '+MT(d.restante)+'.</p>';
   }else if(d.estado==='CONCLUIDO'){
    if(d.pagamento==='PARCIAL'){
     a.innerHTML='<div class="resumo"><b>Serviço concluído!</b><br><span class="small">Falta o pagamento final de 50%: '+MT(d.restante)+'. Depois pode avaliar o serviço.</span></div>'
      +METODOS+'<div class="actions"><button class="btn sun" id="pagar2">Pagar os 50% finais ('+MT(d.restante)+')</button></div>'+ajudaPag
      +'<p class="small">Se houver algum problema com o serviço, ligue ao suporte antes de pagar: <a href="'+tl(KT.suporte)+'">'+esc(KT.suporte)+'</a>.</p>';
     $('#pagar2').onclick=async()=>{try{await api('/api/pagar-final',{codigo:CODIGO,metodo:$('.met',a).value});toast('Pagamento final feito. Obrigado!');carregar();}catch(e){toast(e.message,true);}};
    }else{
     if(d.avaliado){a.innerHTML='<p>Pagamento completo e avaliação enviada. Obrigado!</p>';}
     else{
      a.innerHTML='<p>Pagamento completo. Como foi o serviço?</p><div class="stars" id="stars">'+[1,2,3,4,5].map(n=>'<button type="button" data-n="'+n+'" aria-label="'+n+' estrelas">&#9733;</button>').join('')+'</div>'
       +'<label>Comentário (opcional)<input id="com" maxlength="300"></label><button class="btn" id="avaliar">Enviar avaliação</button>';
      notaSel=0;
      $$('#stars button').forEach(b=>b.onclick=()=>{notaSel=Number(b.dataset.n);$$('#stars button').forEach(x=>x.classList.toggle('on',Number(x.dataset.n)<=notaSel));});
      $('#avaliar').onclick=async()=>{if(!notaSel)return toast('Escolha de 1 a 5 estrelas.',true);
       try{await api('/api/avaliar',{codigo:CODIGO,nota:notaSel,comentario:$('#com').value});toast('Avaliação enviada.');carregar();}catch(e){toast(e.message,true);}};
     }
     if(d.pagamento==='RETIDO'){a.insertAdjacentHTML('beforeend','<p class="small" style="margin-top:12px">O valor fica retido e é libertado ao prestador 24 horas depois do pagamento final. Se algo correu mal, ligue ao suporte antes desse prazo: <a href="'+tl(KT.suporte)+'">'+esc(KT.suporte)+'</a>.</p>');}
    }
   }else if(d.estado==='CANCELADO'){a.innerHTML='<p>Este pedido foi cancelado.'+(d.pagamento==='REEMBOLSADO'?' O valor pago será reembolsado.':'')+'</p>';}
  }
  const box=$('#mapaBox');
  if(d.estado==='EM_CURSO'&&d.posicao){
   box.hidden=false;
   if(!mp){mp=mapa('mapa',[d.lat,d.lng],14);
    L.marker([d.lat,d.lng]).addTo(mp).bindPopup('O seu local');
    mkP=L.circleMarker([d.posicao.lat,d.posicao.lng],{radius:11,color:'#B8305E',fillColor:'#F2B62D',fillOpacity:1,weight:3}).addTo(mp).bindPopup('Prestador');
    mp.fitBounds([[d.lat,d.lng],[d.posicao.lat,d.posicao.lng]],{padding:[40,40]});}
   else{mkP.setLatLng([d.posicao.lat,d.posicao.lng]);}
  }else{box.hidden=true;}
 }
 async function cancelar(){if(!confirm('Cancelar este pedido?'))return;try{await api('/api/cancelar',{codigo:CODIGO});toast('Pedido cancelado.');carregar();}catch(e){toast(e.message,true);}}
 return {
  show(param){
   parar();limparMapa();esconder();CODIGO=param||'';
   if(!TOKEN()){$('#semConta').hidden=false;return;}
   if(!CODIGO){listar();}else{carregar();timer=setInterval(carregar,5000);}
  },
  hide(){parar();}
 };
});
;
registar('prestador',root=>{
 const $=s=>root.querySelector(s),$$=(s,r=root)=>[...r.querySelectorAll(s)];
 let CRED=null,gpsId=null,ultimoEnvio=0,gpsCod='';
 $('#rcat').innerHTML=Object.values(CATS).map(m=>m.cats.map(c=>'<option>'+esc(c)+'</option>').join('')).join('');
 $$('.tab').forEach(t=>t.onclick=()=>{$$('.tab').forEach(x=>x.classList.toggle('on',x===t));$('#prTabEntrar').hidden=t.dataset.t!=='entrar';$('#prTabReg').hidden=t.dataset.t!=='registar';});
 $('#fLogin').onsubmit=async e=>{e.preventDefault();CRED={telefone:$('#lid').value.trim(),senha:$('#lpin').value};await entrar();$('#lpin').value='';};
 $('#fReg').onsubmit=async e=>{e.preventDefault();
  if($('#rsenha').value!==$('#rsenha2').value){toast('As senhas não coincidem.',true);return;}
  try{await api('/api/prestadores',{nome:$('#rnome').value,telefone:$('#rtel').value,senha:$('#rsenha').value,categoria:$('#rcat').value,bairro:$('#rbairro').value});
   const b=$('#regOk');b.hidden=false;
   b.innerHTML='<b>Registo enviado com sucesso!</b><br>A tua conta está <b>PENDENTE</b> de aprovação pela equipa KONEKTA. Podes entrar com o telefone e a senha depois de registares, mas só receberás trabalhos após a aprovação.';
   $('#fReg').reset();
  }catch(er){toast(er.message,true);}};
 $('#prSair').onclick=()=>{sessionStorage.removeItem('konekta_prs');CRED=null;pararGps();$('#prPainel').hidden=true;$('#entrada').hidden=false;};
 async function entrar(){
  try{const d=await api('/api/prestador/painel',CRED);sessionStorage.setItem('konekta_prs',JSON.stringify(CRED));mostrar(d);}
  catch(e){toast(e.message,true);sessionStorage.removeItem('konekta_prs');CRED=null;}
 }
 function mostrar(d){
  $('#entrada').hidden=true;$('#prPainel').hidden=false;
  $('#prNome').textContent=d.nome;
  $('#prInfo').innerHTML=esc(d.id)+' · '+esc(d.categoria)+' · '+esc(d.bairro)+' '+(d.selo?'<span class="tag selo">Prestador KONEKTA</span>':'<span class="tag">Sem selo. Peça o curso rápido à gestão.</span>');
  $('#prAviso').hidden=d.aprovado;
  $('#prStats').innerHTML='<div class="stat"><b>'+d.servicos+'</b><span>Serviços feitos</span></div><div class="stat"><b>'+MT(d.ganhos)+'</b><span>Já recebido</span></div><div class="stat"><b>'+MT(d.retido)+'</b><span>Retido, a libertar</span></div><div class="stat"><b>'+(d.nota||'-')+'</b><span>Nota média</span></div>';
  const j=$('#jobs');
  if(!d.trabalhos.length){j.innerHTML='<p class="small">Ainda não tem trabalhos. Quando a gestão lhe atribuir um pedido com a entrada paga, ele aparece aqui.</p>';return;}
  j.innerHTML=d.trabalhos.map(t=>{
   let bt='';
   if(t.estado==='APROVADO')bt='<button class="btn sm" data-a="iniciar" data-c="'+esc(t.codigo)+'">Iniciar serviço</button>';
   if(t.estado==='EM_CURSO')bt='<button class="btn sun sm" data-a="concluir" data-c="'+esc(t.codigo)+'">Marcar como concluído</button><p class="small" style="margin-top:8px">A partilhar a sua localização com a gestão e o cliente.</p>';
   if(t.estado==='CONCLUIDO'&&t.pagamento==='PARCIAL')bt='<p class="small">Serviço concluído. A aguardar o pagamento final de 50% do cliente.</p>';
   if(t.estado==='CONCLUIDO'&&!t.avaliado&&t.pagamento!=='PARCIAL')bt='<div class="small">Avalie o cliente (visível só à gestão):</div><div class="stars">'+[1,2,3,4,5].map(n=>'<button type="button" data-av="'+n+'" data-c="'+esc(t.codigo)+'" aria-label="'+n+' estrelas">&#9733;</button>').join('')+'</div>';
   const pag=PAGTXT[t.pagamento]?'<span class="tag '+(t.pagamento==='LIBERADO'?'ok':'warn')+'">'+esc(PAGTXT[t.pagamento])+'</span>':'';
   return '<div class="job"><div class="top2"><h3>'+esc(t.categoria)+'</h3><span class="tag">'+esc(etiqueta(t.estado,t.pagamento))+'</span></div>'
    +'<p style="margin:4px 0">'+esc(t.descricao)+'</p><p class="small" style="margin:0 0 8px">Cliente anónimo · '+esc(t.bairro)+(t.endereco?' · '+esc(t.endereco):'')+' · '+esc(t.codigo)+'</p>'
    +'<p style="margin:0 0 10px"><b>Recebe '+MT(t.liquido)+'</b> (já sem os 15%) '+pag+'</p>'+bt+'</div>';
  }).join('');
  $$('[data-a]',j).forEach(b=>b.onclick=async()=>{try{await api('/api/prestador/acao',{...CRED,codigo:b.dataset.c,acao:b.dataset.a});if(b.dataset.a==='concluir')pararGps();await entrar();}catch(e){toast(e.message,true);}});
  $$('[data-av]',j).forEach(b=>b.onclick=async()=>{try{await api('/api/prestador/avaliar',{...CRED,codigo:b.dataset.c,nota:b.dataset.av});toast('Avaliação registada.');await entrar();}catch(e){toast(e.message,true);}});
  const emCurso=d.trabalhos.find(t=>t.estado==='EM_CURSO');
  if(emCurso)iniciarGps(emCurso.codigo);
 }
 function iniciarGps(cod){
  if(gpsId!==null||!navigator.geolocation)return;
  gpsCod=cod;
  gpsId=navigator.geolocation.watchPosition(p=>{
   const n=Date.now();if(n-ultimoEnvio<8000)return;ultimoEnvio=n;
   api('/api/prestador/posicao',{...CRED,codigo:gpsCod,lat:p.coords.latitude,lng:p.coords.longitude}).catch(()=>{});
  },()=>{},{enableHighAccuracy:true,maximumAge:5000});
 }
 function pararGps(){if(gpsId!==null){navigator.geolocation.clearWatch(gpsId);gpsId=null;}}
 setInterval(()=>{if(CRED&&!root.hidden&&!document.hidden)api('/api/prestador/painel',CRED).then(mostrar).catch(()=>{});},15000);
 return {show(){
  if(!CRED){try{const s=sessionStorage.getItem('konekta_prs');if(s){CRED=JSON.parse(s);entrar();}}catch(e){}}
 }};
});
;
registar('gestao',root=>{
 const $=s=>root.querySelector(s),$$=(s,r=root)=>[...r.querySelectorAll(s)];
 let TOKEN=null,D=null,iv=null;
 $('#fPin').onsubmit=async e=>{e.preventDefault();try{const r=await api('/api/gestao/entrar',{pin:$('#pin').value});TOKEN=r.token;sessionStorage.setItem('konekta_gestao',TOKEN);$('#pin').value='';await carregar(true);}catch(err){toast(err.message,true);}};
 $('#gSair').onclick=()=>{if(TOKEN)api('/api/gestao/sair',{token:TOKEN}).catch(()=>{});sessionStorage.removeItem('konekta_gestao');TOKEN=null;D=null;$('#gApp').hidden=true;$('#gLogin').hidden=false;};
 $('#atualizar').onclick=()=>carregar();
 $$('.tab').forEach(t=>t.onclick=()=>{$$('.tab').forEach(x=>x.classList.toggle('on',x===t));$('#vPed').hidden=t.dataset.t!=='ped';$('#vPrs').hidden=t.dataset.t!=='prs';$('#vCli').hidden=t.dataset.t!=='cli';});

 async function carregar(primeira){
  try{D=await api('/api/gestao/painel',{token:TOKEN});sessionStorage.setItem('konekta_gestao',TOKEN);
   $('#gLogin').hidden=true;$('#gApp').hidden=false;render();}
  catch(e){
   if(primeira||!D){toast(e.message,true);sessionStorage.removeItem('konekta_gestao');TOKEN=null;$('#gApp').hidden=true;$('#gLogin').hidden=false;}
  }
 }
 async function acao(rota,dados,msg){try{await api(rota,{token:TOKEN,...dados});toast(msg);await carregar();}catch(e){toast(e.message,true);}}
 function tagEstado(p){
  const c=p.estado==='CONCLUIDO'&&p.pagamento!=='PARCIAL'?'ok':p.estado==='CANCELADO'?'mag':(p.estado==='RECEBIDO'||(p.estado==='CONCLUIDO'&&p.pagamento==='PARCIAL'))?'warn':'';
  return '<span class="tag '+c+'">'+esc(etiqueta(p.estado,p.pagamento))+'</span>';
 }
 function render(){
  const b=D.bd,av=$('#avisoBd');
  if(b.modo==='sqlite'){av.hidden=true;$('#bdOk').textContent='Armazenamento: base de dados SQLite (konekta.db).';}
  else if(b.modo==='ficheiro'){av.hidden=false;$('#bdOk').textContent='';av.textContent='O SQLite não arrancou ('+b.motivo+'). Os dados estão a ser guardados no ficheiro local konekta-dados.txt e não se perdem ao reiniciar.';}
  else{av.hidden=false;$('#bdOk').textContent='';av.textContent='ATENÇÃO: sem armazenamento. Os dados perdem-se ao parar o servidor. Motivo: '+b.motivo;}
  $('#gStats').innerHTML='<div class="stat"><b>'+D.abertos+'</b><span>Pedidos abertos</span></div><div class="stat"><b>'+MT(D.retido)+'</b><span>Retido no KONEKTA Pay</span></div><div class="stat"><b>'+MT(D.comissao)+'</b><span>Comissão ('+Math.round(D.taxa*100)+'%) já ganha</span></div><div class="stat"><b>'+D.clientes.length+'</b><span>Clientes registados</span></div>';
  const v=$('#vPed');
  if(!D.pedidos.length)v.innerHTML='<p class="small">Ainda não há pedidos. Os pedidos dos clientes aparecem aqui.</p>';
  else v.innerHTML=D.pedidos.map(p=>{
   const opts=D.prestadores.filter(x=>x.aprovado).map(x=>'<option value="'+esc(x.id)+'"'+(x.id===p.prestadorId?' selected':'')+'>'+esc(x.nome)+' · '+esc(x.categoria)+' · '+esc(x.bairro)+(x.selo?' ✓':'')+'</option>').join('');
   let ac='';
   if(p.estado==='RECEBIDO'||p.estado==='ORCAMENTO'){
    ac='<div class="row2"><label>Valor total do orçamento (MT)<input type="number" min="1" step="1" id="v_'+esc(p.codigo)+'" value="'+(p.valor||'')+'"></label>'
     +'<label>Prestador<select id="s_'+esc(p.codigo)+'"><option value="">Escolher…</option>'+opts+'</select></label></div>'
     +'<div class="actions"><button class="btn sm" data-o="'+esc(p.codigo)+'">'+(p.estado==='RECEBIDO'?'Enviar orçamento ao cliente':'Atualizar orçamento')+'</button><button class="btn danger sm" data-c="'+esc(p.codigo)+'">Cancelar</button></div>';
   }else if(p.estado==='CONCLUIDO'&&p.pagamento==='PARCIAL'){
    ac='<p class="small">O cliente ainda não pagou os 50% finais: '+MT(p.restante)+'.</p><button class="btn sun sm" data-f="'+esc(p.codigo)+'">Confirmar pagamento final recebido</button>';
   }else if(p.estado==='CONCLUIDO'&&p.pagamento==='RETIDO'){
    ac='<p class="small">Libertação automática ao prestador: '+new Date(p.liberacaoEm).toLocaleString('pt-PT')+'</p><button class="btn sun sm" data-l="'+esc(p.codigo)+'">Libertar pagamento agora</button>';
   }else if(p.estado==='APROVADO'||p.estado==='EM_CURSO'){
    ac='<button class="btn danger sm" data-c="'+esc(p.codigo)+'">Cancelar e reembolsar '+MT(p.pago)+'</button>';
   }
   const pag=PAGTXT[p.pagamento]?' <span class="tag '+(p.pagamento==='LIBERADO'?'ok':'warn')+'">'+esc(PAGTXT[p.pagamento])+(p.metodo?' · '+esc(p.metodo):'')+(p.metodo2&&p.metodo2!==p.metodo?' / '+esc(p.metodo2):'')+'</span>':'';
   const notas=(p.notaPrestador||p.notaCliente)?'<p class="small">Avaliações: prestador '+(p.notaPrestador||'-')+'/5 · cliente '+(p.notaCliente||'-')+'/5'+(p.comentario?' · "'+esc(p.comentario)+'"':'')+'</p>':'';
   return '<div class="job"><div class="top2"><h3>'+esc(p.codigo)+' · '+esc(NOMEMOD[p.modulo])+' · '+esc(p.categoria)+'</h3><span>'+tagEstado(p)+pag+'</span></div>'
    +'<p style="margin:4px 0">'+esc(p.descricao)+'</p>'
    +'<p class="small" style="margin:0 0 8px">Cliente: '+esc(p.nome)+' · <a href="'+tl(p.telefone)+'">'+esc(p.telefone)+'</a> · '+esc(p.bairro)+(p.endereco?' · '+esc(p.endereco):'')+(p.prestadorNome?' · Prestador: '+esc(p.prestadorNome):'')+(p.valor?' · Total '+MT(p.valor)+' · pago '+MT(p.pago):'')+'</p>'
    +notas+ac+'</div>';
  }).join('');
  $$('[data-o]',v).forEach(b=>b.onclick=()=>acao('/api/gestao/orcamento',{codigo:b.dataset.o,valor:$('#v_'+b.dataset.o).value,prestadorId:$('#s_'+b.dataset.o).value},'Orçamento enviado ao cliente.'));
  $$('[data-f]',v).forEach(b=>b.onclick=()=>{if(confirm('Confirmar que os 50% finais foram recebidos?'))acao('/api/gestao/confirmar-final',{codigo:b.dataset.f},'Pagamento final confirmado.');});
  $$('[data-l]',v).forEach(b=>b.onclick=()=>acao('/api/gestao/liberar',{codigo:b.dataset.l},'Pagamento libertado.'));
  $$('[data-c]',v).forEach(b=>b.onclick=()=>{if(confirm('Cancelar este pedido?'))acao('/api/gestao/cancelar',{codigo:b.dataset.c},'Pedido cancelado.');});

  $('#vPrs').innerHTML=D.prestadores.map(x=>
   '<div class="job"><div class="top2"><h3>'+esc(x.nome)+'</h3><span>'+(x.aprovado?'<span class="tag ok">Aprovado</span>':'<span class="tag warn">Por aprovar</span>')+' '+(x.selo?'<span class="tag selo">Selo KONEKTA</span>':'')+'</span></div>'
   +'<p class="small" style="margin:0 0 10px">'+esc(x.id)+' · '+esc(x.categoria)+' · '+esc(x.bairro)+' · '+esc(x.telefone)+' · '+x.servicos+' serviços · nota '+(x.nota||'-')+'</p>'
   +'<div class="actions"><button class="btn sm '+(x.aprovado?'ghost':'')+'" data-p="'+esc(x.id)+'" data-v="'+(!x.aprovado)+'">'+(x.aprovado?'Suspender':'Aprovar')+'</button>'
   +'<button class="btn sm ghost" data-s="'+esc(x.id)+'" data-v="'+(!x.selo)+'">'+(x.selo?'Retirar selo':'Curso concluído: dar selo')+'</button>'
   +'<button class="btn sm ghost" data-n="'+esc(x.id)+'">Gerar novo PIN</button></div></div>').join('');
  $$('[data-p]',$('#vPrs')).forEach(b=>b.onclick=()=>acao('/api/gestao/prestador',{id:b.dataset.p,aprovado:b.dataset.v},'Prestador atualizado.'));
  $$('[data-s]',$('#vPrs')).forEach(b=>b.onclick=()=>acao('/api/gestao/prestador',{id:b.dataset.s,selo:b.dataset.v},'Selo atualizado.'));
  $$('[data-n]',$('#vPrs')).forEach(b=>b.onclick=async()=>{if(!confirm('Gerar um novo PIN para este prestador?'))return;
   try{const r=await api('/api/gestao/prestador-pin',{token:TOKEN,id:b.dataset.n});alert('Novo PIN de '+r.nome+': '+r.pin+' — diga-o ao prestador por telefone.');}catch(e){toast(e.message,true);}});

  $('#vCli').innerHTML=D.clientes.length?D.clientes.map(c=>
   '<div class="job"><div class="top2"><h3>'+esc(c.nome)+'</h3><span class="tag">'+c.pedidos+' pedidos</span></div>'
   +'<p class="small" style="margin:0 0 10px">'+esc(c.id)+' · <a href="'+tl(c.telefone)+'">'+esc(c.telefone)+'</a></p>'
   +'<button class="btn sm ghost" data-r="'+esc(c.id)+'">Repor senha</button></div>').join(''):'<p class="small">Ainda não há clientes registados.</p>';
  $$('[data-r]',$('#vCli')).forEach(b=>b.onclick=async()=>{if(!confirm('Criar uma senha temporária para este cliente? A senha atual deixa de funcionar.'))return;
   try{const r=await api('/api/gestao/repor-senha',{token:TOKEN,id:b.dataset.r});alert('Senha temporária de '+r.nome+': '+r.senha+' — diga-a ao cliente por telefone e peça para a alterar em Conta.');}catch(e){toast(e.message,true);}});
 }
 return {
  show(){
   if(!TOKEN){try{const s=sessionStorage.getItem('konekta_gestao');if(s){TOKEN=s;carregar(true);}}catch(e){}}
   clearInterval(iv);
   iv=setInterval(()=>{if(TOKEN&&!document.hidden&&!(document.activeElement&&document.activeElement.matches('input,select')))carregar();},20000);
  },
  hide(){clearInterval(iv);iv=null;}
 };
});
