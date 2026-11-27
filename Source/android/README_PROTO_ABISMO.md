# Operação Abismo VR — protótipo de cockpit submarino

O modo Abismo reutiliza o renderizador estéreo do StormVR e o giroscópio do aparelho. Esta etapa prioriza ambientação e legibilidade dos comandos.

## Direção visual

A cena é inteiramente vista de dentro de um submarino compacto, sem janela direta para o oceano. O casco fechado tem piso e teto metálicos, escotilha estanque, nervuras estruturais, tubulações de lastro e refrigeração, braçadeiras, cabos, armários laterais, cilindros, bateria, luminária e console frontal.

O console reúne três telas simuladas:
- câmera externa de baixa definição, com ruído e uma silhueta distante;
- mapa de navegação incompleto com rota e ponto de interesse;
- sonar central com círculos de alcance, varredura e ecos.

Há também mostradores analógicos, LEDs de lastro e luzes de estado. As telas e leituras são elementos visuais de protótipo; ainda não representam sensores ou uma missão navegável completa.

A atmosfera é inspirada em jogos de horror submarino de cabine fechada, sem reproduzir personagens, arte, mapas ou interfaces protegidas de *Iron Lung*.

## Controles — somente pelas mãos

O olhar e o giroscópio servem apenas para olhar ao redor. Eles **não acionam** os comandos.
- Na primeira utilização, mostre **as duas mãos juntas** à câmera por um instante. O jogo calibra esquerda/direita antes de aceitar uma mão isolada.
- Depois da calibração, **Mão esquerda / Azul / SUBIR:** faça uma pinça para bombear ar no lastro e aumentar a flutuabilidade.
- **Mão direita / Laranja / DESCER:** faça uma pinça para liberar ar e reduzir a flutuabilidade.
- Abra os dedos para soltar. Faça uma nova pinça para aplicar outro ajuste.
- Os comandos agora são push-buttons menores, montados mais à frente no apoio baixo, para não ficarem tão perto do jogador nem exigirem braços erguidos.
- O aro curto dá feedback visual ao acionar. A dinâmica vertical tem inércia e correnteza simplificadas.

O modo Abismo usa o rastreamento das duas mãos pela câmera. A permissão da câmera é necessária para jogar com os comandos.

## Áudio ambiente

Durante o mergulho, o protótipo reproduz um som procedural de água abafada, com bolhas ocasionais em volume baixo. O áudio pausa quando o app sai de foco e para ao sair do modo VR. Não há gritos, sustos, impactos repentinos ou sons de terror.

## Mapa, coordenadas e pressão

Um mapa físico foi colocado na parede frontal, acima dos instrumentos. Ele mostra a rota de referência, a posição inicial e o destino. O visor apresenta coordenadas **X/Y de referência** e **Z** acompanha a profundidade simulada. Como o protótipo ainda não tem navegação horizontal, X/Y não representam deslocamento real.

Ao ultrapassar o limite de profundidade, a pressão rompe uma seção da tubulação lateral. Bolhas saem do trecho rompido, uma luz vermelha alerta o jogador e o indicador de O2 começa a cair. A taxa de perda aumenta com a profundidade. O jogador deve usar **SUBIR** para reduzir a profundidade; o vazamento permanece ativo nesta versão.

## O que ainda não está pronto

1. A missão com começo, objetivo, tarefas e final.
2. A câmera externa com exploração real do ambiente.
3. Navegação horizontal funcional e detecção real de alvos pelo sonar.
4. Sistemas de energia, reparo de casco e objetivos de missão com consequências jogáveis.
5. Teste de legibilidade, conforto, identificação das mãos e desempenho no Galaxy A35 com o VR Box.

A simulação de lastro atual é didática e simplificada. Ela não representa um modelo hidrodinâmico ou de submarino real.

## Mesa de xadrez

A mesa 3D de xadrez fica na **Sala VR**, fora do modo Abismo. É cenário/teste de gestos nesta versão, não um tabuleiro com regras e partidas jogáveis.

## Desempenho e compatibilidade

O modo Abismo usa renderização estéreo leve. O desempenho real depende da iluminação, temperatura e capacidade do aparelho. A compilação Android não substitui o teste físico no Galaxy A35/VR Box.
