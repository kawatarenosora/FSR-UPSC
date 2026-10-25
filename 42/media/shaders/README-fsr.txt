# FSR-UPSC shaders (FSR path)

fsr_blit.vert + fsr_easu.frag + fsr_rcas.frag: fullscreen resolve
pair for the FSR1/bicubic modes. Game convention (#version 330,
DIFFUSE/vColor/vUV1, texture2D 3-arg form) so the stock GLSL
downgrade path keeps working.

The same math is embedded as strings in fsrupsc.Resolve.java
(CPU-verified model: EASU step as tight as bilinear, RCAS sharp=0 is
identity, all outputs inside the neighbourhood range) and compiled at
runtime there -- these files are the readable mirrors of the embedded
sources. Edits here must be copied back into Resolve.java or they are
inert.
