<template>
  <svg
    class="svg-container"
    viewBox="0 0 450 400"
    xmlns="http://www.w3.org/2000/svg"
    overflow="visible"
    fill="none"
    style="overflow:visible; background:transparent"
  >
    <defs>
      <clipPath id="black-clip">
        <rect x="0" y="0" width="450" height="390" />
      </clipPath>
    </defs>

    <!-- Black（中等胶囊，最后层） -->
    <g class="drop-anim" style="animation-delay: 0.2s">
      <g class="breathe" style="animation-delay: 0.5s">
        <g clip-path="url(#black-clip)">
          <g id="black" class="char">
            <rect x="188" y="150" width="88" height="205" rx="44" fill="var(--c-black)" />
            <g class="faces">
              <g class="face face-default">
                <g class="eye-blink" style="animation-delay: 1.5s">
                  <circle cx="214" cy="200" r="10" fill="#FFF" />
                  <g class="eye-tracker">
                    <circle cx="214" cy="200" r="4.5" fill="var(--c-black)" class="pupil" />
                  </g>
                </g>
                <g class="eye-blink" style="animation-delay: 1.5s">
                  <circle cx="250" cy="200" r="10" fill="#FFF" />
                  <g class="eye-tracker">
                    <circle cx="250" cy="200" r="4.5" fill="var(--c-black)" class="pupil" />
                  </g>
                </g>
                <path d="M 222 228 Q 232 238 242 228" fill="none" stroke="#FFF" stroke-width="2.5" stroke-linecap="round" />
              </g>
              <g class="face face-hidden">
                <path d="M 204 200 L 224 200" fill="none" stroke="#FFF" stroke-width="3.5" stroke-linecap="round" />
                <path d="M 240 200 L 260 200" fill="none" stroke="#FFF" stroke-width="3.5" stroke-linecap="round" />
                <path d="M 222 228 Q 228 226 232 228 Q 236 230 242 228" fill="none" stroke="#FFF" stroke-width="2.5" stroke-linecap="round" />
              </g>
              <g class="face face-visible">
                <g class="eye-blink" style="animation-delay: 1.5s">
                  <circle cx="214" cy="198" r="11" fill="#FFF" />
                  <g class="eye-tracker">
                    <circle cx="214" cy="198" r="5" fill="var(--c-black)" class="pupil" />
                  </g>
                </g>
                <g class="eye-blink" style="animation-delay: 1.5s">
                  <circle cx="250" cy="198" r="11" fill="#FFF" />
                  <g class="eye-tracker">
                    <circle cx="250" cy="198" r="5" fill="var(--c-black)" class="pupil" />
                  </g>
                </g>
                <circle cx="232" cy="232" r="5" fill="none" stroke="#FFF" stroke-width="2.5" />
              </g>
              <g class="face face-happy">
                <path d="M 207 196 Q 214 190 221 196" fill="none" stroke="#FFF" stroke-width="3" stroke-linecap="round" />
                <path d="M 243 196 Q 250 190 257 196" fill="none" stroke="#FFF" stroke-width="3" stroke-linecap="round" />
                <path d="M 218 230 Q 232 244 246 230" fill="none" stroke="#FFF" stroke-width="3" stroke-linecap="round" />
              </g>
            </g>
          </g>
        </g>
      </g>
    </g>

    <!-- Purple（高胶囊，左侧） -->
    <g class="arc-anim" style="animation-delay: 0.35s">
      <g class="breathe" style="animation-delay: 0s">
        <g id="purple" class="char">
          <rect x="55" y="55" width="110" height="300" rx="40" fill="var(--c-purple)" />
          <g class="faces">
            <g class="face face-default">
              <g class="eye-blink" style="animation-delay: 0s">
                <circle cx="85" cy="120" r="12" fill="#FFF" />
                <g class="eye-tracker">
                  <circle cx="85" cy="120" r="5" fill="var(--c-black)" class="pupil" />
                </g>
              </g>
              <g class="eye-blink" style="animation-delay: 0s">
                <circle cx="135" cy="120" r="12" fill="#FFF" />
                <g class="eye-tracker">
                  <circle cx="135" cy="120" r="5" fill="var(--c-black)" class="pupil" />
                </g>
              </g>
              <path d="M 97 155 Q 110 165 123 155" fill="none" stroke="#FFF" stroke-width="3" stroke-linecap="round" />
            </g>
            <g class="face face-hidden">
              <path d="M 75 120 L 95 120" fill="none" stroke="#FFF" stroke-width="4" stroke-linecap="round" />
              <path d="M 125 120 L 145 120" fill="none" stroke="#FFF" stroke-width="4" stroke-linecap="round" />
              <path d="M 97 155 Q 104 152 110 155 Q 116 158 123 155" fill="none" stroke="#FFF" stroke-width="3" stroke-linecap="round" />
            </g>
            <g class="face face-visible">
              <g class="eye-blink" style="animation-delay: 0s">
                <circle cx="85" cy="120" r="13" fill="#FFF" />
                <g class="eye-tracker">
                  <circle cx="85" cy="120" r="5" fill="var(--c-black)" class="pupil" />
                </g>
              </g>
              <g class="eye-blink" style="animation-delay: 0s">
                <circle cx="135" cy="120" r="13" fill="#FFF" />
                <g class="eye-tracker">
                  <circle cx="135" cy="120" r="5" fill="var(--c-black)" class="pupil" />
                </g>
              </g>
              <circle cx="110" cy="158" r="5" fill="none" stroke="#FFF" stroke-width="3" />
            </g>
            <g class="face face-happy">
              <path d="M 78 116 Q 85 110 92 116" fill="none" stroke="#FFF" stroke-width="3" stroke-linecap="round" />
              <path d="M 128 116 Q 135 110 142 116" fill="none" stroke="#FFF" stroke-width="3" stroke-linecap="round" />
              <path d="M 93 150 Q 110 168 127 150" fill="none" stroke="#FFF" stroke-width="3.5" stroke-linecap="round" />
            </g>
          </g>
        </g>
      </g>
    </g>

    <!-- Yellow（大穹顶，右侧） -->
    <g class="jelly-anim" style="animation-delay: 0.4s">
      <g class="breathe" style="animation-delay: 1s">
        <g id="yellow" class="char">
          <path d="M 255 390 A 90 155 0 0 1 435 390 Z" fill="var(--c-yellow)" />
          <g class="faces">
            <g class="face face-default">
              <g class="eye-tracker">
                <circle cx="320" cy="300" r="6" fill="var(--c-black)" class="pupil eye-blink" style="animation-delay: 0.8s" />
                <circle cx="370" cy="300" r="6" fill="var(--c-black)" class="pupil eye-blink" style="animation-delay: 0.8s" />
              </g>
              <path d="M 333 328 Q 345 338 357 328" fill="none" stroke="var(--c-black)" stroke-width="3" stroke-linecap="round" />
            </g>
            <g class="face face-hidden">
              <path d="M 314 300 L 326 300" fill="none" stroke="var(--c-black)" stroke-width="3" stroke-linecap="round" />
              <path d="M 364 300 L 376 300" fill="none" stroke="var(--c-black)" stroke-width="3" stroke-linecap="round" />
              <path d="M 333 328 Q 339 326 345 328 Q 351 330 357 328" fill="none" stroke="var(--c-black)" stroke-width="3" stroke-linecap="round" />
            </g>
            <g class="face face-visible">
              <g class="eye-tracker">
                <circle cx="320" cy="297" r="7" fill="var(--c-black)" class="pupil" />
                <circle cx="370" cy="297" r="7" fill="var(--c-black)" class="pupil" />
              </g>
              <circle cx="345" cy="330" r="6" fill="none" stroke="var(--c-black)" stroke-width="2.5" />
            </g>
            <g class="face face-happy">
              <path d="M 317 296 Q 320 290 326 296" fill="none" stroke="var(--c-black)" stroke-width="2.5" stroke-linecap="round" />
              <path d="M 367 296 Q 370 290 376 296" fill="none" stroke="var(--c-black)" stroke-width="2.5" stroke-linecap="round" />
              <path d="M 333 325 Q 345 340 357 325" fill="none" stroke="var(--c-black)" stroke-width="3" stroke-linecap="round" />
            </g>
          </g>
        </g>
      </g>
    </g>

    <!-- Orange（巨大穹顶，最前层） -->
    <g class="jelly-anim" style="animation-delay: 0.05s">
      <g class="breathe" style="animation-delay: 1.5s">
        <g id="orange" class="char">
          <path d="M 10 390 A 155 150 0 0 1 320 390 Z" fill="var(--c-orange)" />
          <g class="faces">
            <g class="face face-default">
              <g class="eye-tracker">
                <circle cx="130" cy="310" r="6" fill="var(--c-black)" class="pupil eye-blink" style="animation-delay: 3s" />
                <circle cx="200" cy="310" r="6" fill="var(--c-black)" class="pupil eye-blink" style="animation-delay: 3s" />
              </g>
              <path d="M 150 345 Q 165 360 180 345" fill="none" stroke="var(--c-black)" stroke-width="3.5" stroke-linecap="round" />
            </g>
            <g class="face face-hidden">
              <path d="M 120 315 L 140 315" fill="none" stroke="var(--c-black)" stroke-width="3.5" stroke-linecap="round" />
              <path d="M 190 315 L 210 315" fill="none" stroke="var(--c-black)" stroke-width="3.5" stroke-linecap="round" />
              <path d="M 150 350 Q 158 347 165 350 Q 172 353 180 350" fill="none" stroke="var(--c-black)" stroke-width="3.5" stroke-linecap="round" />
            </g>
            <g class="face face-visible">
              <g class="eye-tracker">
                <circle cx="130" cy="305" r="7" fill="var(--c-black)" class="pupil" />
                <circle cx="200" cy="305" r="7" fill="var(--c-black)" class="pupil" />
              </g>
              <circle cx="165" cy="348" r="8" fill="none" stroke="var(--c-black)" stroke-width="3.5" />
            </g>
            <g class="face face-happy">
              <path d="M 122 306 Q 130 300 138 306" fill="none" stroke="var(--c-black)" stroke-width="3" stroke-linecap="round" />
              <path d="M 192 306 Q 200 300 208 306" fill="none" stroke="var(--c-black)" stroke-width="3" stroke-linecap="round" />
              <path d="M 145 342 Q 165 362 185 342" fill="none" stroke="var(--c-black)" stroke-width="3.5" stroke-linecap="round" />
            </g>
          </g>
        </g>
      </g>
    </g>
  </svg>
</template>
