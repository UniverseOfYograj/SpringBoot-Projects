/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './index.html',
    './src/**/*.{ts,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        // --- Railora Brand Tokens ---
        navy: {
          50:  '#eef2f8',
          100: '#d5e0ee',
          200: '#b0c4e0',
          300: '#7ea1ca',
          400: '#4d7cb0',
          500: '#2a5a91',
          600: '#1a3f6e',
          700: '#142B49', // PRIMARY NAVY
          800: '#0f2038',
          900: '#0a1525',
          950: '#060d17',
        },
        teal: {
          50:  '#f0fdfb',
          100: '#ccfbf5',
          200: '#99f5ec',
          300: '#5ceadf',
          400: '#22d6cc',
          500: '#0F766E', // TEAL ACCENT
          600: '#0d6560',
          700: '#0a534d',
          800: '#08403c',
          900: '#052e2a',
        },
        // --- Status Tokens ---
        status: {
          success: {
            bg: '#F0FDF4',
            border: '#BBF7D0',
            text: '#166534',
          },
          warning: {
            bg: '#FFFBEB',
            border: '#FDE68A',
            text: '#92400E',
          },
          danger: {
            bg: '#FEF2F2',
            border: '#FECACA',
            text: '#991B1B',
          },
          info: {
            bg: '#F0FDFA',
            border: '#99F6E4',
            text: '#115E59',
          },
        },
        // --- Neutral Surface Tokens ---
        surface: {
          base:     '#FAFAF9', // warm-white body bg
          card:     '#FFFFFF', // card / modal surface
          muted:    '#F1F5F9', // slate-100 dividers & ghost areas
          border:   '#E2E8F0', // slate-200 input borders
          subtle:   '#CBD5E1', // slate-300 disabled states
        },
        // --- Text Tokens ---
        ink: {
          primary:   '#0F172A', // slate-900
          secondary: '#334155', // slate-700
          muted:     '#64748B', // slate-500
          disabled:  '#94A3B8', // slate-400
          inverse:   '#FFFFFF',
        },
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      },
      fontSize: {
        '2xs': ['0.625rem', { lineHeight: '1rem' }],
      },
      borderRadius: {
        'sm':  '4px',
        'md':  '6px',
        'lg':  '8px',
        'xl':  '12px',
        '2xl': '16px',
      },
      boxShadow: {
        'card':  '0 1px 3px 0 rgb(0 0 0 / 0.07), 0 1px 2px -1px rgb(0 0 0 / 0.07)',
        'card-hover': '0 4px 6px -1px rgb(0 0 0 / 0.08), 0 2px 4px -2px rgb(0 0 0 / 0.08)',
        'modal': '0 20px 25px -5px rgb(0 0 0 / 0.10), 0 8px 10px -6px rgb(0 0 0 / 0.10)',
      },
      transitionDuration: {
        'fast':   '150ms',
        'normal': '200ms',
      },
      transitionTimingFunction: {
        'ease-out-smooth': 'cubic-bezier(0.16, 1, 0.3, 1)',
      },
    },
  },
  plugins: [],
};
