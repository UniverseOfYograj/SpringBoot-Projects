import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import Button from '@/components/ui/Button';
import Input from '@/components/ui/Input';
import Badge from '@/components/ui/Badge';
import Card, { CardTitle, CardContent } from '@/components/ui/Card';

describe('Button component', () => {
  it('renders with children and responds to click', () => {
    const handleClick = vi.fn();
    render(<Button onClick={handleClick}>Click Me</Button>);
    const btn = screen.getByRole('button', { name: /click me/i });
    expect(btn).toBeDefined();
    fireEvent.click(btn);
    expect(handleClick).toHaveBeenCalledTimes(1);
  });

  it('handles loading state properly', () => {
    render(<Button isLoading>Submit</Button>);
    const btn = screen.getByRole('button');
    expect(btn.getAttribute('aria-busy')).toBe('true');
    expect(btn.hasAttribute('disabled')).toBe(true);
  });

  it('applies variant classes', () => {
    const { container } = render(<Button variant="accent">Accent</Button>);
    expect(container.firstChild).toBeDefined();
    const btn = screen.getByRole('button');
    expect(btn.className).toContain('bg-teal-500');
  });
});

describe('Input component', () => {
  it('renders label and input with proper accessibility associations', () => {
    render(<Input label="Station Code" placeholder="NDLS" id="station-code-input" />);
    const label = screen.getByText('Station Code');
    const input = screen.getByPlaceholderText('NDLS');
    expect(label.getAttribute('for')).toBe('station-code-input');
    expect(input.getAttribute('id')).toBe('station-code-input');
  });

  it('renders error message and sets aria-invalid', () => {
    render(<Input label="Station" error="Invalid code" id="station-input" />);
    const input = screen.getByRole('textbox');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByRole('alert').textContent).toBe('Invalid code');
  });
});

describe('Badge component', () => {
  it('renders badge with dot indicator', () => {
    render(<Badge variant="teal" dot>Available</Badge>);
    expect(screen.getByText('Available')).toBeDefined();
  });
});

describe('Card component', () => {
  it('renders card with title and content', () => {
    render(
      <Card>
        <CardTitle>Rajdhani Express</CardTitle>
        <CardContent>Departs at 16:50</CardContent>
      </Card>,
    );
    expect(screen.getByText('Rajdhani Express')).toBeDefined();
    expect(screen.getByText('Departs at 16:50')).toBeDefined();
  });
});
