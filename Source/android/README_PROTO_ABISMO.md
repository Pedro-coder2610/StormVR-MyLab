# Operação Abismo VR — protótipo de cockpit submarino

O modo Abismo reutiliza o renderizador estéreo do StormVR, o giroscópio e o rastreador MediaPipe/One Euro.

## Conceito visual atual

A cena agora é vista de dentro de um submarino compacto, com casco octogonal fechado, piso e teto metálicos, escotilha estanque atrás do operador, tubulações laterais e console frontal. Não há janela direta para o oceano nesta etapa: o sonar é a principal representação visual do exterior.

O painel possui uma tela de sonar com varredura animada, ecos, dois mostradores analógicos e dois comandos físicos de lastro. Esta é a base visual e interativa; a missão, narrativa e objetivos serão construídos em etapas posteriores.

## Controles do cockpit

- O comando azul bombeia ar para o lastro e aumenta a flutuabilidade.
- O comando laranja libera ar do lastro e reduz a flutuabilidade.
- Sem uma mão apontando, mantenha o olhar sobre um dos comandos para confirmar depois de aproximadamente 1,2 segundo.
- Com rastreamento de mãos ativo, aponte para a esquerda ou direita e faça uma pinça para confirmar.
- Uma mão aberta ou relaxada não deve bloquear a alternativa de controle pelo olhar.
- A variação do lastro altera gradualmente a leitura de profundidade; há inércia simplificada e correnteza de teste.

## Próximas etapas de design

1. Dar função de jogo ao sonar (detectar um ponto de interesse, rota ou sinal).
2. Definir a primeira missão e uma sequência de tarefas dentro do submarino.
3. Acrescentar consequências e feedback aos sistemas do casco, oxigênio, energia e navegação.
4. Testar legibilidade, conforto e desempenho no Galaxy A35 com o VR Box.

A física atual é um protótipo didático simplificado. Não representa um modelo hidrodinâmico ou de submarino real.

## Mesa de xadrez

A mesa 3D de xadrez fica na **Sala VR**, fora do modo Abismo. É cenário/teste de gestos nesta versão, não um tabuleiro com regras e partidas jogáveis.

## Desempenho e compatibilidade

O modo Abismo usa renderização estéreo leve e rastreamento de mãos a 20 fps. O desempenho real depende da iluminação, distância das mãos, temperatura e capacidade do aparelho. A compilação Android não substitui o teste físico no Galaxy A35/VR Box.
