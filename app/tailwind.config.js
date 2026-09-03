/**
 * Tailwind theme, in Google Material Design 3 token shape with backwards-compatible aliases.
 *
 * Colors are CSS variables so a single `dark` class on the root switches the
 * whole tree. The raw values live in global.css; nothing else in the app
 * writes a color.
 */
/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  content: ['./src/**/*.{ts,tsx}', './App.tsx'],
  presets: [require('nativewind/preset')],
  theme: {
    extend: {
      opacity: {
        38: '0.38',
      },
      colors: {
        border: 'hsl(var(--border))',
        input: 'hsl(var(--input))',
        ring: 'hsl(var(--ring))',
        background: 'hsl(var(--background))',
        foreground: 'hsl(var(--foreground))',
        primary: {
          DEFAULT: 'hsl(var(--primary))',
          foreground: 'hsl(var(--primary-foreground))',
          container: 'hsl(var(--primary-container))',
          'on-container': 'hsl(var(--on-primary-container))',
        },
        'on-primary': 'hsl(var(--primary-foreground))',
        'on-primary-container': 'hsl(var(--on-primary-container))',
        secondary: {
          DEFAULT: 'hsl(var(--secondary))',
          foreground: 'hsl(var(--secondary-foreground))',
          container: 'hsl(var(--secondary-container))',
          'on-container': 'hsl(var(--on-secondary-container))',
        },
        'on-secondary': 'hsl(var(--secondary-foreground))',
        'on-secondary-container': 'hsl(var(--on-secondary-container))',
        tertiary: {
          DEFAULT: 'hsl(var(--tertiary))',
          foreground: 'hsl(var(--tertiary-foreground))',
          container: 'hsl(var(--tertiary-container))',
          'on-container': 'hsl(var(--on-tertiary-container))',
        },
        'on-tertiary': 'hsl(var(--tertiary-foreground))',
        'on-tertiary-container': 'hsl(var(--on-tertiary-container))',
        surface: {
          DEFAULT: 'hsl(var(--surface))',
          on: 'hsl(var(--on-surface))',
          variant: 'hsl(var(--surface-variant))',
          'on-variant': 'hsl(var(--on-surface-variant))',
          container: {
            DEFAULT: 'hsl(var(--surface-container))',
            lowest: 'hsl(var(--surface-container-lowest))',
            low: 'hsl(var(--surface-container-low))',
            high: 'hsl(var(--surface-container-high))',
            highest: 'hsl(var(--surface-container-highest))',
          },
        },
        'on-surface': 'hsl(var(--on-surface))',
        'on-surface-variant': 'hsl(var(--on-surface-variant))',
        outline: {
          DEFAULT: 'hsl(var(--outline))',
          variant: 'hsl(var(--outline-variant))',
        },
        destructive: {
          DEFAULT: 'hsl(var(--destructive))',
          foreground: 'hsl(var(--destructive-foreground))',
          container: 'hsl(var(--destructive-container))',
          'on-container': 'hsl(var(--on-destructive-container))',
        },
        'on-destructive': 'hsl(var(--destructive-foreground))',
        'on-destructive-container': 'hsl(var(--on-destructive-container))',
        muted: {
          DEFAULT: 'hsl(var(--muted))',
          foreground: 'hsl(var(--muted-foreground))',
        },
        accent: {
          DEFAULT: 'hsl(var(--accent))',
          foreground: 'hsl(var(--accent-foreground))',
        },
        popover: {
          DEFAULT: 'hsl(var(--popover))',
          foreground: 'hsl(var(--popover-foreground))',
        },
        card: {
          DEFAULT: 'hsl(var(--card))',
          foreground: 'hsl(var(--card-foreground))',
        },
        terminal: {
          DEFAULT: 'hsl(var(--terminal))',
          foreground: 'hsl(var(--terminal-foreground))',
        },
      },
      borderRadius: {
        xs: '4px',
        sm: '8px',
        md: '12px',
        lg: '16px',
        xl: '28px',
        '2xl': '16px',
        '3xl': '28px',
      },
    },
  },
  plugins: [],
};
