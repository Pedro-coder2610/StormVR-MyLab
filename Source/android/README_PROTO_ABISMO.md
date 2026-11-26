# Operação Abismo VR — integração com mãos estabilizadas

O modo Abismo reutiliza o renderizador estéreo do StormVR, o giroscópio e o rastreador MediaPipe/One Euro.

## Controles

- Com as mãos visíveis, aponte com o indicador para a esquerda para selecionar a válvula azul (adicionar ar) ou para a direita para selecionar a válvula laranja (liberar ar).
- Faça uma pinça com polegar e indicador para confirmar a válvula.
- Abra a mão ou pare de apontar para soltar a seleção e permitir outro comando.
- As duas mãos voltam a ser desenhadas na cena. A cor muda conforme o gesto: mão aberta (ciano), apontando (amarelo), punho fechado (coral), pinça (verde) e positivo (lilás); em repouso, esquerda azul-gelo e direita coral.
- Sem câmera ou sem mão detectada, mantenha o olhar sobre a válvula por aproximadamente 1,2 segundo como alternativa.
- A cabeça continua sendo orientada pelo giroscópio; a câmera é usada para rastrear as mãos, não para 6DoF da cabeça.

## Missão — Operação Abismo

A missão agora tem um objetivo jogável: estabilizar o módulo de resgate dentro do aro antes que o oxigênio acabe.

- **Válvula azul:** adiciona ar em pequenos incrementos e aumenta o empuxo.
- **Válvula laranja:** libera ar e reduz o empuxo.
- **Inércia:** a cápsula acelera e desacelera gradualmente; evite corrigir demais.
- **Correnteza:** uma força variável interfere na subida/descida e é indicada por setas na cena.
- **Oxigênio:** há uma reserva de 180 segundos para concluir a missão.
- **Estabilidade:** mantenha a cápsula próxima do centro do aro e com pouca velocidade por 4 segundos para concluir o resgate.
- **Falha:** o oxigênio esgotado encerra a tentativa.

A física é uma simulação didática simplificada, não um solucionador hidrodinâmico calibrado. As luzes do HUD representam a reserva de oxigênio e o progresso de estabilidade.

## Mesa de xadrez

A mesa 3D de xadrez fica na **Sala VR** (não dentro do cenário submarino), à frente do jogador, com as 32 peças na posição inicial. Ela é uma peça de cenário/teste de gestos nesta versão, ainda não um tabuleiro com regras e partidas jogáveis.

## Desempenho e compatibilidade

O modo Abismo usa rastreamento de duas mãos a 20 fps e renderização leve para equilibrar a fluidez no celular. A qualidade real depende da iluminação, distância das mãos, temperatura e desempenho do aparelho. Teste no Galaxy A35/VR Box depois de compilar o APK.
